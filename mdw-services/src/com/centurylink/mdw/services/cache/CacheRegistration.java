package com.centurylink.mdw.services.cache;

import com.centurylink.mdw.annotations.Parameter;
import com.centurylink.mdw.annotations.RegisteredService;
import com.centurylink.mdw.bpm.ApplicationCacheDocument;
import com.centurylink.mdw.bpm.ApplicationCacheDocument.ApplicationCache;
import com.centurylink.mdw.bpm.CacheDocument.Cache;
import com.centurylink.mdw.bpm.PropertyDocument.Property;
import com.centurylink.mdw.cache.CacheService;
import com.centurylink.mdw.cache.PreloadableCache;
import com.centurylink.mdw.cache.VariableTypeCache;
import com.centurylink.mdw.common.service.SystemMessages;
import com.centurylink.mdw.config.PropertyManager;
import com.centurylink.mdw.model.JsonObject;
import com.centurylink.mdw.model.system.Bulletin;
import com.centurylink.mdw.model.system.SystemMessage.Level;
import com.centurylink.mdw.service.data.activity.ImplementorCache;
import com.centurylink.mdw.services.messenger.InternalMessenger;
import com.centurylink.mdw.services.request.HandlerCache;
import com.centurylink.mdw.services.util.InitialRequest;
import com.centurylink.mdw.spring.SpringAppContext;
import com.centurylink.mdw.startup.StartupException;
import com.centurylink.mdw.startup.StartupService;
import com.centurylink.mdw.util.file.FileHelper;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;
import org.apache.commons.lang.StringUtils;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlOptions;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Startup class that manages registration of all the caches
 */

public class CacheRegistration implements StartupService {

    private static final String APPLICATION_CACHE_FILE_NAME = "application-cache.xml";
    private static StandardLogger logger = LoggerUtil.getStandardLogger();
    private static final Map<String,CacheService> allCaches = new LinkedHashMap<>();

    private static CacheRegistration instance;
    public static synchronized CacheRegistration getInstance() {
        if (instance == null)
            instance = new CacheRegistration();
        return instance;
    }


    /**
     * Method that gets invoked when the server comes up
     * Load all the cache objects when the server starts
     */
    public void onStartup() {
        try {
            preloadCaches();
            SpringAppContext.getInstance().loadPackageContexts();  // trigger dynamic context loading
            preloadDynamicCaches();
            // implementor, handler and variableType caches rely on kotlin from preloadDynamicCaches()
            ImplementorCache implementorCache = new ImplementorCache();
            implementorCache.loadCache();
            allCaches.put(ImplementorCache.class.getSimpleName(), implementorCache);
            HandlerCache handlerCache = new HandlerCache();
            handlerCache.loadCache();
            allCaches.put(HandlerCache.class.getSimpleName(), handlerCache);
            VariableTypeCache variableTypeCache = new VariableTypeCache();
            variableTypeCache.loadCache();
            allCaches.put(VariableTypeCache.class.getSimpleName(), variableTypeCache);
        }
        catch (Exception ex){
            String message = "Failed to load caches";
            logger.error(message, ex);
            throw new StartupException(message, ex);
        }
    }

    /**
     * Load all the cache objects when the server starts
     */
    private void preloadCaches() throws IOException, XmlException {
        Map<String,Properties> caches = getPreloadCacheSpecs();
        for (String cacheName : caches.keySet()) {
            Properties cacheProps = caches.get(cacheName);
            String cacheClassName = cacheProps.getProperty("ClassName");
            logger.info(" - loading cache " + cacheName);
            CacheService cachingObj = getCacheInstance(cacheClassName, cacheProps);
            if (cachingObj != null) {
                long before = System.currentTimeMillis();
                if (cachingObj instanceof PreloadableCache) {
                    ((PreloadableCache)cachingObj).loadCache();
                }
                synchronized(allCaches) {
                    allCaches.put(cacheName, cachingObj);
                }
                logger.debug("    - " + cacheName + " loaded in " + (System.currentTimeMillis() - before) + " ms");
            }
            else {
                logger.warn("Caching Class is  invalid. Name-->"+cacheClassName);
            }
        }
    }

    /**
     * Load caches registered as dynamic java services.
     */
    private void preloadDynamicCaches() {
        List<CacheService> dynamicCacheServices = CacheRegistry.getInstance().getDynamicCacheServices();
        for (CacheService dynamicCacheService : dynamicCacheServices) {
            if (dynamicCacheService instanceof PreloadableCache) {
                try {
                    PreloadableCache preloadableCache = (PreloadableCache)dynamicCacheService;
                    RegisteredService regServ = preloadableCache.getClass().getAnnotation(RegisteredService.class);
                    Map<String,String> params = new HashMap<>();
                    Parameter[] parameters = regServ.parameters();
                    if (parameters != null) {
                        for (Parameter parameter : parameters) {
                            if (parameter.name().length() > 0)
                                params.put(parameter.name(), parameter.value());
                        }
                    }
                    preloadableCache.initialize(params);
                    preloadableCache.loadCache();
                }
                catch (Exception ex) {
                    logger.error("Failed to preload " + dynamicCacheService.getClass(), ex);
                }
            }
            synchronized(allCaches) {
                allCaches.put(dynamicCacheService.getClass().getName(), dynamicCacheService);
            }
        }
    }

    private CacheService getCacheInstance(String className, Properties cacheProps) {
        try {
            Class<? extends CacheService> cl = Class.forName(className).asSubclass(CacheService.class);
            CacheService cache = cl.newInstance();
            if (cache instanceof PreloadableCache) {
                ((PreloadableCache)cache).initialize(getMap(cacheProps));
            }
            return cache;
        }
        catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            return null;
        }
    }

    private Map<String,String> getMap(Properties properties) {
        if (properties == null)
            return null;

        Map<String,String> params = new HashMap<String,String>();
        for (Object name : properties.keySet()) {
            if (name != null)
              params.put(name.toString(), properties.getProperty(name.toString()));
        }
        return params;
    }

    /**
     * Method that gets invoked when the server shuts down
     */
    public void onShutdown(){
    }

    public synchronized void refreshCaches() throws StartupException {
        Bulletin bulletin = null;
        try {
            bulletin = SystemMessages.bulletinOn("Cache refresh in progress...");
            String propmgr = PropertyManager.class.getName();
            CacheRegistry.getInstance().clearDynamicServices();
            synchronized (allCaches) {
                for (String cacheName : allCaches.keySet()) {
                    if (!cacheName.equals(propmgr))
                        refreshCache(cacheName);
                }
            }
            SpringAppContext.getInstance().loadPackageContexts();  // trigger dynamic context loading
            new InitialRequest().submit();
            SystemMessages.bulletinOff(bulletin, "Cache refresh completed");

        } catch (Exception ex) {
            String message = "Failed to load caches";
            logger.error(message, ex);
            if (bulletin != null) {
                SystemMessages.bulletinOff(bulletin, Level.Error, "Cache refresh failed");
            }
            throw new StartupException(message, ex);
        }
    }

    public CacheService getCache(String name) {
        return allCaches.get(name);
    }

    /**
     * Refreshes a particular cache by name.
     * @param cacheName the cache to refresh
     */
    public void refreshCache(String cacheName) {
        CacheService cache = allCaches.get(cacheName);
        if (cache != null) {
            logger.info(" - refresh cache " + cacheName);
            try {
                cache.refreshCache();
            } catch (Exception e) {
                logger.error("failed to refresh cache", e);
            }
        }
    }

    private Map<String,Properties> getPreloadCacheSpecs() throws IOException, XmlException {
        Map<String,String> depedencyCacheMap = new HashMap<String, String>();
        ApplicationCacheDocument appCacheDoc = null;
        Map<String,Properties> retPropsTemp = new HashMap<String,Properties>();
        Map<String,Properties> retProps = new LinkedHashMap<String,Properties>();
        List<String> tempList;
        try (InputStream stream = FileHelper.openConfigurationFile(APPLICATION_CACHE_FILE_NAME)) {
            appCacheDoc = ApplicationCacheDocument.Factory.parse(stream, new XmlOptions());
            ApplicationCache appCache = appCacheDoc.getApplicationCache();
            for (Cache cache : appCache.getCacheList()) {
                Properties cacheProps = new Properties();
                for (Property prop : cache.getPropertyList()) {
                    if ("dependsOn".equalsIgnoreCase(prop.getName()))
                        depedencyCacheMap.put(cache.getName(), prop.getStringValue());
                    cacheProps.put(prop.getName(), prop.getStringValue());
                }
                retPropsTemp.put(cache.getName(), cacheProps);
            }
            tempList = getNamesBasedOnDependencyHierarchy(depedencyCacheMap);

            for (String cache : tempList) {
                if (!cache.equals(PropertyManager.class.getName()))
                    retProps.put(cache, retPropsTemp.get(cache));
            }

            for (String name : retPropsTemp.keySet()) {
                if (!name.equals(PropertyManager.class.getName())) {
                    if (!retProps.containsKey(name))
                        retProps.put(name, retPropsTemp.get(name));
                }
            }
            return retProps;
        }
    }

    public void registerCache(String name, CacheService cache) {
        logger.info("Register cache " + name);
        synchronized(allCaches) {
            allCaches.put(name, cache);
        }
    }

    public static void broadcastRefresh(String cacheNames, InternalMessenger messenger) {
        try {
            JSONObject json = new JsonObject();
            json.put("ACTION", "REFRESH_CACHES");
            if (!StringUtils.isBlank(cacheNames)) json.put("CACHE_NAMES", cacheNames);
            messenger.broadcastMessage(json.toString());
        } catch (Exception e) {
            logger.error("Failed to publish cashe refresh message", e);
        }
    }

    private List<String> getNamesBasedOnDependencyHierarchy(Map<String,String> depedencyCacheMap) {
        List<String> orderedCaches = new ArrayList<String>();
        for (String key : depedencyCacheMap.keySet()) {
            int keyIndex = orderedCaches.indexOf(key);
            for (String cache : depedencyCacheMap.get(key).split(",")) {
                int cacheIdx = orderedCaches.indexOf(cache);
                if (keyIndex >= 0 && cacheIdx >= 0 && cacheIdx > keyIndex) {
                        orderedCaches.remove(cache);
                        orderedCaches.add(keyIndex, cache);
                }
                if (cacheIdx < 0) {
                    if (keyIndex < 0)
                        orderedCaches.add(cache);
                    else
                        orderedCaches.add(keyIndex, cache);
                }
            }
            if (keyIndex < 0)
                orderedCaches.add(key);
        }
        return orderedCaches;
    }

}
package com.centurylink.mdw.camel;

import com.centurylink.mdw.annotations.RegisteredService;
import com.centurylink.mdw.cache.CacheService;
import com.centurylink.mdw.cache.CachingException;
import com.centurylink.mdw.cache.PreloadableCache;
import com.centurylink.mdw.cache.asset.AssetCache;
import com.centurylink.mdw.model.asset.Asset;
import com.centurylink.mdw.model.asset.AssetVersionSpec;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelContextAware;
import org.apache.camel.model.RoutesDefinition;
import org.springframework.beans.factory.annotation.Autowired;

import javax.naming.NamingException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

@RegisteredService(CacheService.class)
public class CamelRouteCache implements PreloadableCache, CamelContextAware {

    private static StandardLogger logger = LoggerUtil.getStandardLogger();
    private static volatile Map<String,RoutesDefinitionRuleSet> routesMap = Collections.synchronizedMap(new TreeMap<String,RoutesDefinitionRuleSet>());
    private static String[] preLoaded;

    @Autowired
    private static CamelContext camelContext;

    public CamelContext getCamelContext() {
        return camelContext;
    }

    public void setCamelContext(CamelContext context) {
        camelContext = context;
    }

    public CamelRouteCache() {
        camelContext = getCamelContext();
    }

    public void initialize(Map<String,String> params) {
        if (params != null) {
            String preLoadString = params.get("PreLoaded");
            if (preLoadString != null && preLoadString.trim().length() > 0) {
                List<String> preLoadList = new ArrayList<String>();
                preLoaded = preLoadString.split("\\\n");
                for (int i = 0; i < preLoaded.length; i++) {
                    String preLoad = preLoaded[i].trim();
                    if (!preLoad.isEmpty())
                      preLoadList.add(preLoad);
                }
                preLoaded = preLoadList.toArray(new String[]{});
            }
        }
    }


    public static RoutesDefinitionRuleSet RoutesDefinitionRuleSet(String name) {
        return getRoutesDefinitionRuleSet(name, null);
    }

    public static RoutesDefinitionRuleSet getRoutesDefinitionRuleSet(String name, String modifier) {

        Key key = new Key(name, modifier);

        RoutesDefinitionRuleSet routesRuleSet = routesMap.get(key.toString());

        if (routesRuleSet == null) {
            try {
                synchronized (routesMap) {
                    routesRuleSet = routesMap.get(key.toString());
                    if (routesRuleSet == null) {
                        logger.info("Loading RoutesDefinition RuleSet based on key: " + key);
                        routesRuleSet = loadRoutesDefinitionRuleSet(key);
                        routesMap.put(key.toString(), routesRuleSet);
                    }
                }
            }
            catch (Exception ex) {
                logger.error(ex.getMessage(), ex);
            }
        }
        return routesRuleSet;
    }

    /**
     * Routes definition based on the route definition name and version
     *
     * @param routeVersionSpec
     * @param modifier
     * @return RoutesDefinitionRuleSet
     */
    public static RoutesDefinitionRuleSet getRoutesDefinitionRuleSet(AssetVersionSpec routeVersionSpec, String modifier) {
        Key key = new Key(routeVersionSpec, modifier);
        RoutesDefinitionRuleSet routesRuleSet = routesMap.get(key.toString());
        if (routesRuleSet == null) {
            try {
                synchronized (routesMap) {
                    routesRuleSet = routesMap.get(key.toString());
                    if (routesRuleSet == null) {
                        logger.info("Loading RoutesDefinition RuleSet based on key: " + key);
                        routesRuleSet = loadRoutesDefinitionRuleSet(key);
                        routesMap.put(key.toString(), routesRuleSet);
                    }
                }
            }
            catch (Exception ex) {
                logger.error(ex.getMessage(), ex);
            }
        }
        return routesRuleSet;
    }

    public static RoutesDefinition getRoutesDefinition(String name) {
        return getRoutesDefinition(name, null);
    }

    public static RoutesDefinition getRoutesDefinition(String name, String modifier) {
        RoutesDefinitionRuleSet rdrs = getRoutesDefinitionRuleSet(name, modifier);
        if (rdrs == null)
            return null;
        else
            return rdrs.getRoutesDefinition();
    }

    public void clearCache() {
        getCamelContext();
        synchronized (routesMap) {
            for (String routeName : routesMap.keySet()) {
                RoutesDefinitionRuleSet rdrs = routesMap.get(routeName);
                try {
                    camelContext.removeRouteDefinitions(rdrs.getRoutesDefinition().getRoutes());
                }
                catch (Exception ex) {
                    logger.error(ex.getMessage(), ex);
                }
            }
            routesMap.clear();
        }
    }

    public int getCacheSize() {
        return routesMap.size();
    }

    public void loadCache() throws CachingException {
        if (preLoaded != null) {   // otherwise load is performed lazily
            try {
                synchronized (routesMap) {
                    for (String preLoadKey : preLoaded) {
                        Key key = new Key(preLoadKey);

                        logger.info("PreLoading Camel Route based on key: " + key);
                        RoutesDefinitionRuleSet rdrs = loadRoutesDefinitionRuleSet(key);
                        if (rdrs != null) {
                            routesMap.put(preLoadKey, rdrs);
                        }
                    }
                }
            }
            catch (Exception ex) {
                throw new CachingException(ex.getMessage(), ex);
            }
        }
    }

    public void refreshCache() throws CachingException {
        synchronized (routesMap) {
            clearCache();
            loadCache();
        }
    }

    private static RoutesDefinitionRuleSet loadRoutesDefinitionRuleSet(Key key) throws CachingException {
        if (camelContext == null) {
            try {
              camelContext = TomcatCamelContext.getInstance().getCamelContext();
            }
            catch (NamingException ex) {
                throw new CachingException("Cannot access Tomcat CamelContext", ex);
            }
        }
        if (camelContext == null)
            throw new CachingException("Cannot access CamelContext");

        Asset asset = null;
        try {
            getAsset(key);
        } catch (IOException ex) {
            throw new CachingException("Error loading rule set: '" + key.name + "'", ex);
        }
        if (asset == null)
            throw new CachingException("No rule set found for: '" + key.name + "'");

        String appendToUri = "/" + (key.name == null ? key.routeVersionSpec.getQualifiedName() : key.name);
        if (key.modifier != null && key.modifier.trim().length() > 0)
            appendToUri += "?" + key.modifier;

        // TODO better expression
        String subst = asset.getText().replaceAll("mdw:workflow/this", "mdw:workflow" + appendToUri);
        if (subst.equals(asset.getText()))
            subst = asset.getText().replaceAll("mdw:workflow", "mdw:workflow" + appendToUri);

        asset.setText(subst);

        if (logger.isDebugEnabled())
            logger.debug("Loading substituted camel routes " + asset.getLabel() + ":\n" + asset.getText() + "\n================================");

        String extension = asset.getExtension();

        CamelContext localCamelContext = camelContext;

        if (extension.equals("camel")) {
            // Spring DSL Camel Route
            logger.info("Loading Camel Route '" + asset.getLabel() + "' with CamelContext: " + localCamelContext);
            try {
                ByteArrayInputStream inStream = new ByteArrayInputStream(asset.getContent());
                RoutesDefinition routesDefinition = localCamelContext.loadRoutesDefinition(inStream);
                if (localCamelContext.hasComponent("mdw") == null)
                    localCamelContext.addComponent("mdw", new MdwComponent());
                localCamelContext.addRouteDefinitions(routesDefinition.getRoutes());
                return new RoutesDefinitionRuleSet(routesDefinition, asset);
            }
            catch (Exception ex) {
                logger.error(ex.getMessage(), ex);
                throw new CachingException(ex.getMessage(), ex);
            }
        }

        throw new CachingException("Unsupported asset extension: " + extension);
    }

    public static Asset getAsset(Key key) throws IOException {
        Asset ruleSet = null;
        if (key.routeVersionSpec != null) {
            ruleSet = AssetCache.getAsset(key.routeVersionSpec);
        }
        if (ruleSet != null)
            return ruleSet;

        String ruleSetName = key.name == null ? key.routeVersionSpec.getQualifiedName() : key.name;
        return AssetCache.getAsset(ruleSetName);
    }

    /**
     * eg: MyRoutesName~myModifier{attr1=attr1val,attr2=attr2val}
     * eg with versionspec : MyPackage/MyRoutesName v[.5,1)~myModifier{attr1=attr1val,attr2=attr2val}
     */
    static class Key {
        String name;
        String modifier;
        AssetVersionSpec routeVersionSpec;

        public Key(String name, String mod) {
            this.name = name;
            this.modifier = mod;
        }

        public Key(AssetVersionSpec routeVersionSpec, String modifier) {
            super();
            this.modifier = modifier;
            this.routeVersionSpec = routeVersionSpec;
        }

        public Key(String stringVal) {
            String toParse = stringVal;
            int squig = toParse.indexOf('~');
            if (squig >= 0) {
                modifier = toParse.substring(squig + 1);
                toParse = toParse.substring(0, squig);
            }
            AssetVersionSpec routeSpec = AssetVersionSpec.parse(toParse);
            if (routeSpec != null) {
                this.routeVersionSpec = routeSpec;
            } else {
                name = toParse;
            }
        }

        public String toString() {
            String key = routeVersionSpec == null ? name : routeVersionSpec.toString();
            if (modifier != null)
                key += "~" + modifier;
            return key;
        }
    }

}

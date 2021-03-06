package com.centurylink.mdw.react;

import com.centurylink.mdw.cache.CachingException;
import org.json.JSONArray;
import org.json.JSONObject;

import com.centurylink.mdw.annotations.RegisteredService;
import com.centurylink.mdw.app.ApplicationContext;
import com.centurylink.mdw.cache.CacheService;
import com.centurylink.mdw.cache.asset.AssetCache;
import com.centurylink.mdw.model.asset.Asset;

import java.io.IOException;

/**
 * Caches the UI route mapping from JSX asset to associated location hash route
 * so that MDW's AngularJS base webapp can properly handle JSX asset routing.
 */
@RegisteredService(value=CacheService.class)
public class UiRouteCache implements CacheService {

    // Null if not loaded; empty if no routes.json asset.
    private static JSONArray routesJson;

    @Override
    public void clearCache() {
        routesJson = null;
    }

    @Override
    public void refreshCache() throws Exception {
        loadCache();
    }

    public static synchronized void loadCache() {
        String hubOverridePackage = ApplicationContext.getHubOverridePackage();
        String assetPath = hubOverridePackage + ".js/routes.json";
        try {
            Asset routesAsset = AssetCache.getAsset(assetPath);
            if (routesAsset == null) {
                routesJson = new JSONArray();
            } else {
                routesJson = new JSONArray(routesAsset.getText());
            }
        } catch (IOException ex) {
            throw new CachingException("Error loading " + assetPath);
        }
    }

    public static String getPath(String jsxAsset, boolean parameterized) {
        if (routesJson == null)
            loadCache();
        for (int i = 0; i < routesJson.length(); i++) {
            JSONObject route = routesJson.getJSONObject(i);
            String asset = route.getString("asset");
            if (asset.endsWith(".jsx")) {
                if (asset.equals(jsxAsset)) {
                    String path = route.getString("path");
                    boolean hasParam = path.indexOf("/:") >= 0;
                    if ((parameterized && hasParam) || (!parameterized && !hasParam))
                        return path;
                }
            }
        }
        return null;
    }

    public static JSONArray getRoutes() {
        if (routesJson == null)
            loadCache();
        return routesJson;
    }
}

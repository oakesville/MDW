package com.centurylink.mdw.hub.servlet;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import com.centurylink.mdw.app.Templates;
import com.centurylink.mdw.model.asset.api.AssetInfo;
import com.centurylink.mdw.model.asset.Pagelet;
import com.centurylink.mdw.model.asset.Pagelet.Widget;
import com.centurylink.mdw.monitor.MonitorAttributes;
import com.centurylink.mdw.monitor.MonitorRegistry;
import com.centurylink.mdw.monitor.ProcessMonitor;
import com.centurylink.mdw.services.AssetServices;
import com.centurylink.mdw.services.ServiceLocator;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;

/**
 * Access for MDW template content.  TODO: Caching
 */
@WebServlet(urlPatterns={"/template/*"})
public class TemplateServlet extends HttpServlet {

    private static StandardLogger logger = LoggerUtil.getStandardLogger();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String path = request.getPathInfo().substring(1);
        String templateContent = Templates.get(path);
        if (templateContent == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
        else if (path.equals("configurator/processMonitoring.json")) {
            // special handling for dynamic values
            JSONObject json = new JSONObject(templateContent);
            json.remove("comment");
            try {
                Pagelet pagelet = new Pagelet(json.getJSONObject("pagelet"));
                JSONObject monitorsJson = new JSONObject(Templates.get("configurator/monitors.json"));
                monitorsJson.remove("comment");
                Widget monitoringWidget = new Widget(monitorsJson);
                pagelet.addWidget(monitoringWidget);
                AssetServices assetServices = ServiceLocator.getAssetServices();
                JSONArray rows = new JSONArray();
                for (ProcessMonitor processMonitor : MonitorRegistry.getInstance().getProcessMonitors()) {
                    AssetInfo implAsset = assetServices.getImplAsset(processMonitor.getClass().getName());
                    JSONArray row = MonitorAttributes.getRowDefault(implAsset, processMonitor.getClass());
                    if (row != null) {
                        rows.put(row);
                    }
                }
                if (rows.length() > 0)
                    monitoringWidget.setAttribute("default", rows.toString());
                json.put("pagelet", pagelet.getJson());
                response.getWriter().print(json.toString(2));
                return;
            }
            catch (Exception ex) {
                logger.error(ex.getMessage(), ex);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }

        response.getWriter().print(templateContent);
    }
}

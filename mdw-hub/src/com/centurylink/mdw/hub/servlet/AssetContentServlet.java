package com.centurylink.mdw.hub.servlet;

import com.centurylink.mdw.app.ApplicationContext;
import com.centurylink.mdw.common.service.AuthorizationException;
import com.centurylink.mdw.common.service.Query;
import com.centurylink.mdw.common.service.ServiceException;
import com.centurylink.mdw.common.service.SystemMessages;
import com.centurylink.mdw.config.PropertyException;
import com.centurylink.mdw.config.PropertyManager;
import com.centurylink.mdw.constant.PropertyNames;
import com.centurylink.mdw.dataaccess.DataAccessException;
import com.centurylink.mdw.git.VersionControlGit;
import com.centurylink.mdw.hub.servlet.asset.StagedAssetServer;
import com.centurylink.mdw.hub.servlet.asset.StagedAssetUpdater;
import com.centurylink.mdw.hub.servlet.asset.VersionedAssetServer;
import com.centurylink.mdw.model.Status;
import com.centurylink.mdw.model.StatusResponse;
import com.centurylink.mdw.model.asset.AssetPath;
import com.centurylink.mdw.model.asset.ContentTypes;
import com.centurylink.mdw.model.asset.api.AssetInfo;
import com.centurylink.mdw.model.system.Bulletin;
import com.centurylink.mdw.model.system.SystemMessage.Level;
import com.centurylink.mdw.model.user.*;
import com.centurylink.mdw.model.user.UserAction.Action;
import com.centurylink.mdw.model.user.UserAction.Entity;
import com.centurylink.mdw.model.workflow.Process;
import com.centurylink.mdw.service.data.user.UserGroupCache;
import com.centurylink.mdw.services.AssetServices;
import com.centurylink.mdw.services.ServiceLocator;
import com.centurylink.mdw.services.WorkflowServices;
import com.centurylink.mdw.services.asset.Renderer;
import com.centurylink.mdw.services.asset.RenderingException;
import com.centurylink.mdw.services.cache.CacheRegistration;
import com.centurylink.mdw.util.DateHelper;
import com.centurylink.mdw.util.file.FileHelper;
import com.centurylink.mdw.util.file.ZipHelper;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;
import com.centurylink.mdw.util.timer.LoggerProgressMonitor;
import com.centurylink.mdw.util.timer.ProgressMonitor;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides read/update access for raw asset content.
 */
@WebServlet(urlPatterns = { "/asset/*" }, loadOnStartup = 1)
public class AssetContentServlet extends HttpServlet {

    private static final StandardLogger logger = LoggerUtil.getStandardLogger();

    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        File assetRoot = ApplicationContext.getAssetRoot();
        if (!assetRoot.isDirectory()) {
            new StatusResponder(response).writeResponse(new StatusResponse(Status.NOT_FOUND));
            return;
        }
        String stagingCuid = request.getParameter("stagingUser");
        String path = request.getPathInfo().substring(1);
        try {
            authorizeForView(request.getSession(), path, stagingCuid);
        }
        catch (AuthorizationException ex) {
            logger.error(ex.getMessage(), ex);
            new StatusResponder(response).writeResponse(new StatusResponse(ex.getCode(), ex.getMessage()));
            return;
        }

        if ("packages".equals(path)) {
            if (stagingCuid != null) {
                new StatusResponder(response).writeResponse(new StatusResponse(Status.NOT_FOUND));
                return;
            }

            String packages = request.getParameter("packages");
            if (packages == null) {
                new StatusResponder(response).writeResponse(new StatusResponse(Status.BAD_REQUEST, "Missing parameter: 'packages'"));
                return;
            }
            else {
                String recursive = request.getParameter("recursive");
                boolean includeSubPkgs = recursive == null ? false : recursive.equalsIgnoreCase("true") ? true : false;
                response.setHeader("Content-Disposition", "attachment;filename=\"packages.zip\"");
                response.setContentType("application/octet-stream");
                try {
                    List<File> includes = new ArrayList<>();
                    for (String pkgName : getPackageNames(packages))
                        includes.add(new File(assetRoot + "/" + pkgName.replace('.', '/')));
                    ZipHelper.writeZipWith(assetRoot, response.getOutputStream(), includes, includeSubPkgs);
                }
                catch (Exception ex) {
                    logger.error(ex.getMessage(), ex);
                }
                return;
            }
        }
        else {
            if (path.indexOf('/') == -1) {
                // must be qualified
                new StatusResponder(response).writeResponse(new StatusResponse(Status.NOT_FOUND));
                return;
            }
            String[] segments = path.split("/");
            if (segments.length == 3 && segments[2].indexOf('.') > 0) {
                // specific asset version
                path = segments[0] + '/' + segments[1];
                String version = segments[2];
                try {
                    AssetServices assetServices = ServiceLocator.getAssetServices();
                    AssetInfo currentAsset = assetServices.getAsset(path);
                    if (currentAsset == null || !version.equals(currentAsset.getJson().optString("version"))) {
                        new VersionedAssetServer(request, response).serveAsset(path, version);
                        return;
                    }
                }
                catch (ServiceException ex) {
                    logger.error(ex.getMessage(), ex);
                    new StatusResponder(response).writeResponse(new StatusResponse(ex.getCode(), ex.getMessage()));
                }
            }
            if (stagingCuid != null) {
                try {
                    new StagedAssetServer(request, response).serveAsset(path, stagingCuid);
                }
                catch (ServiceException ex) {
                    logger.error(ex.getMessage(), ex);
                    new StatusResponder(response).writeResponse(new StatusResponse(ex.getCode(), ex.getMessage()));
                }
                return;
            }

            boolean gitRemote = false;
            String render = request.getParameter("render");
            AssetPath assetPath = new AssetPath(path);
            File assetFile = new File(assetRoot + "/" + assetPath.toPath());
            gitRemote = "true".equalsIgnoreCase(request.getParameter("gitRemote"));
            if (!assetFile.isFile()) {
                // check for instanceId
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash > 0) {
                    try {
                        Long instanceId = Long.parseLong(path.substring(lastSlash + 1));
                        String p = path.substring(0, lastSlash);
                        lastSlash = p.lastIndexOf('/');
                        String pkgName = p.substring(0, lastSlash);
                        String assetName = p.substring(lastSlash + 1);
                        if (assetName.endsWith(".proc")) {
                            WorkflowServices workflowServices = ServiceLocator.getWorkflowServices();
                            try {
                                Process process = workflowServices.getInstanceDefinition(pkgName + "/" + assetName, instanceId);
                                if (process == null)
                                    throw new ServiceException(ServiceException.NOT_FOUND, "Instance definition not found: " + path);
                                response.setContentType(process.getContentType());
                                response.getOutputStream().print(process.getJson().toString(2));
                            } catch (ServiceException ex) {
                                if (ex.getCode() != ServiceException.NOT_FOUND)
                                    logger.error(ex.getMessage(), ex);
                                StatusResponse sr = new StatusResponse(ex.getCode(), ex.getMessage());
                                response.setStatus(sr.getStatus().getCode());
                                response.getWriter().println(sr.getJson().toString(2));
                            }
                            return;
                        }
                    }
                    catch (NumberFormatException ex) {
                        // not an instance path -- handle as regular asset
                    }
                }
            }

            boolean download = "true".equalsIgnoreCase(request.getParameter("download"));
            if (render == null) {
                response.setContentType(ContentTypes.getContentType(assetFile));
                if (download) {
                    response.setHeader("Content-Disposition", "attachment;filename=\"" + assetFile.getName() + "\"");
                    response.setContentType("application/octet-stream");
                }
            }
            else {
                try {
                    Renderer renderer = ServiceLocator.getAssetServices().getRenderer(path, render);
                    if (renderer == null)
                        throw new RenderingException(ServiceException.NOT_FOUND, "Renderer not found: " + render);
                    Map<String,String> options = new HashMap<>();
                    Enumeration<String> paramNames = request.getParameterNames();
                    while (paramNames.hasMoreElements()) {
                        String paramName = paramNames.nextElement();
                        options.put(paramName, request.getParameter(paramName));
                    }
                    if (download) {
                        response.setHeader("Content-Disposition", "attachment;filename=\"" + renderer.getFileName() + "\"");
                        response.setContentType("application/octet-stream");
                    }
                    else {
                        response.setContentType(ContentTypes.getContentType(render));
                    }
                    response.getOutputStream().write(renderer.render(options));
                }
                catch (ServiceException ex) {
                    logger.error(ex.getMessage(), ex);
                    StatusResponse sr = new StatusResponse(ex.getCode(), ex.getMessage());
                    response.setStatus(sr.getStatus().getCode());
                    response.getWriter().println(sr.getJson().toString(2));
                }
                return;
            }

            InputStream in = null;
            OutputStream out = response.getOutputStream();
            try {
                if (gitRemote) {
                    String branch = PropertyManager.getProperty(PropertyNames.MDW_GIT_BRANCH);
                    if (branch == null)
                        throw new PropertyException("Missing required property: " + PropertyNames.MDW_GIT_BRANCH);
                    AssetServices assetServices = ServiceLocator.getAssetServices();
                    VersionControlGit vcGit = assetServices.getVersionControl();
                    String gitPath = vcGit.getRelativePath(assetFile.toPath());
                    in = vcGit.getRemoteContentStream(branch, gitPath);
                    if (in == null)
                        throw new IOException("Git remote not found: " + gitPath);
                }
                else {
                    if (!assetFile.isFile()) {
                        StatusResponse sr = new StatusResponse(Status.NOT_FOUND, "Asset file '" + assetFile + "' not found");
                        response.setStatus(sr.getStatus().getCode());
                        out.write(sr.getJson().toString(2).getBytes());
                        return;
                    }
                    in = new FileInputStream(assetFile);
                }

                int read = 0;
                byte[] bytes = new byte[1024];
                while ((read = in.read(bytes)) != -1)
                    out.write(bytes, 0, read);
            }
            catch (Exception ex) {
                logger.error(ex.getMessage(), ex);
            }
            finally {
                if (in != null)
                    in.close();
            }
        }
    }

    /**
     * Distributed operations support does not include package import.
     * authorization for distributed requests handled by issue #222.
     */
    protected void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        File assetRoot = ApplicationContext.getAssetRoot();
        if (!assetRoot.isDirectory()) {
            new StatusResponder(response).writeResponse(StatusResponse.forCode(Status.NOT_FOUND.getCode()));
            return;
        }

        String stagingCuid = request.getParameter("stagingUser");
        String path = request.getPathInfo().substring(1);
        Bulletin bulletin = null;
        try {
            AssetServices assetServices = ServiceLocator.getAssetServices();
            try {
                VersionControlGit vcs = assetServices.getVersionControl();
                if ("packages".equals(path)) {
                    if (stagingCuid != null) {
                        new StatusResponder(response).writeResponse(new StatusResponse(Status.NOT_FOUND));
                        return;
                    }
                    authorizeForUpdate(request.getSession(), Action.Import, Entity.Package, "Package zip", false);
                    String contentType = request.getContentType();
                    boolean isZip = "application/zip".equals(contentType);
                    if (!isZip)
                        throw new ServiceException(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Unsupported content: " + contentType);
                    File tempDir = new File(ApplicationContext.getTempDirectory());
                    File tempFile = new File(tempDir + "/packageImport_"
                            + DateHelper.filenameDateToString(new Date()) + ".zip");
                    logger.info("Saving package import temporary file: " + tempFile);
                    FileHelper.writeToFile(request.getInputStream(), tempFile);
                    ProgressMonitor progressMonitor = new LoggerProgressMonitor(logger);
                    progressMonitor.start("Unzipping " + tempFile + " into: " + assetRoot);
                    logger.info("Unzipping " + tempFile + " into: " + assetRoot);
                    ZipHelper.unzip(tempFile, assetRoot, null, null, true);
                    bulletin = null;
                    Thread thread = new Thread() {
                        @Override
                        public void run() {
                            this.setName("AssetPackagesCacheRefresh-thread");
                            CacheRegistration.getInstance().refreshCaches();
                        }
                    };
                    thread.start();
                    progressMonitor.done();
                }
                else {
                    String[] segments = path.split("/");
                    if (segments.length == 3 && segments[2].indexOf('.') > 0) {
                        // specific asset version
                        path = segments[0] + '/' + segments[1];
                    }
                    int slashes = 0;
                    Matcher matcher = Pattern.compile("/").matcher(path);
                    while (matcher.find())
                        slashes++;
                    boolean isInstance = slashes > 1;

                    authorizeForUpdate(request.getSession(), Action.Change, Entity.Asset, path, isInstance);

                    if (stagingCuid != null) {
                        User stagingUser = UserGroupCache.getUser(stagingCuid);
                        if (stagingUser == null)
                            throw new ServiceException(ServiceException.NOT_AUTHORIZED, "User not found: " + stagingCuid);
                        new StagedAssetUpdater(request).updateAsset(path, stagingUser);
                        new StatusResponder(response).writeResponse(new StatusResponse(Status.OK));
                        return;
                    }

                    int firstSlash = path.indexOf('/');
                    if (firstSlash == -1 || firstSlash > path.length() - 2)
                        throw new ServiceException(ServiceException.BAD_REQUEST, "Bad path: " + path);
                    String pkgName = path.substring(0, firstSlash);
                    if (isInstance) {
                        int lastSlash = path.lastIndexOf('/');
                        String assetName = path.substring(firstSlash + 1, lastSlash);
                        try {
                            long instanceId = Long.parseLong(path.substring(lastSlash + 1));
                            if (assetName.endsWith(".proc")) {
                                byte[] content = readContent(request);
                                Process process = Process.fromString(new String(content));
                                process.setName(assetName);
                                process.setPackageName(pkgName);
                                logger.info("Saving asset instance " + pkgName + "/" + assetName + ": " + instanceId);
                                WorkflowServices workflowServices = ServiceLocator.getWorkflowServices();
                                workflowServices.saveInstanceDefinition(pkgName + "/" + assetName, instanceId, process);
                            }
                            else {
                                throw new ServiceException(ServiceException.NOT_IMPLEMENTED, "Unsupported asset type: " + assetName);
                            }
                        }
                        catch (NumberFormatException ex) {
                            throw new ServiceException(ServiceException.BAD_REQUEST, "Bad instance id: " + path.substring(lastSlash + 1));
                        }
                    }
                    else {
                        response.setHeader("Allow", "GET");
                        throw new ServiceException(ServiceException.NOT_ALLOWED, "Direct asset update not allowed");
                    }

                    response.getWriter().write(new StatusResponse(200, "OK").getJson().toString(2));
                }
            }
            catch (ServiceException ex) {
                logger.error(ex.getMessage(), ex);
                SystemMessages.bulletinOff(bulletin, Level.Error, "Asset import failed: " + ex.getMessage());
                response.getWriter().write(ex.getStatusResponse().getJson().toString(2));
                StatusResponse sr = new StatusResponse(ex.getCode(), ex.getMessage());
                response.setStatus(sr.getStatus().getCode());
                response.getWriter().println(sr.getJson().toString(2));
            }
        }
        catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            SystemMessages.bulletinOff(bulletin, Level.Error, "Asset import failed: " + ex.getMessage());
            StatusResponse sr = new StatusResponse(Status.INTERNAL_SERVER_ERROR, ex.getMessage());
            response.setStatus(sr.getStatus().getCode());
            response.getWriter().println(sr.getJson().toString(2));
        }
    }

    private byte[] readContent(HttpServletRequest request) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        InputStream is = request.getInputStream();
        int read = 0;
        byte[] bytes = new byte[1024];
        while ((read = is.read(bytes)) != -1)
            baos.write(bytes, 0, read);
        return baos.toByteArray();
    }

    private String[] getPackageNames(String packagesParam) {
        Map<String, String> params = new HashMap<String, String>();
        params.put("packages", packagesParam);
        Query query = new Query("", params);
        return query.getArrayFilter("packages");
    }

    /**
     * Also audit logs (if not distributed propagation).
     */
    private void authorizeForUpdate(HttpSession session, Action action, Entity entity,
            String includes, boolean isInstance) throws AuthorizationException, DataAccessException {
        AuthenticatedUser user = (AuthenticatedUser) session.getAttribute("authenticatedUser");

        if (user == null)
            throw new AuthorizationException(AuthorizationException.NOT_AUTHORIZED, "Authentication failure");

        else if (isInstance) {
            if (!user.hasRole(Role.PROCESS_EXECUTION) && !user.hasRole(Workgroup.SITE_ADMIN_GROUP))
                throw new AuthorizationException(AuthorizationException.FORBIDDEN,
                        "User " + user.getCuid() + " not authorized for this action");
        }
        else {
            if (!user.hasRole(Role.ASSET_DESIGN) && !user.hasRole(Workgroup.SITE_ADMIN_GROUP)) {
                throw new AuthorizationException(AuthorizationException.FORBIDDEN,
                        "User " + user.getCuid() + " not authorized for this action");
            }
        }

        logger.info("Asset mod request received from user: " + user.getCuid() + " for: " + includes);
        UserAction userAction = new UserAction(user.getCuid(), action, entity, 0L, includes);
        userAction.setSource(getClass().getSimpleName());
        ServiceLocator.getUserServices().auditLog(userAction);
    }

    /**
     * Only if "Asset View" role exists.  Web resource assets are excluded.
     * Staged assets for different users require Site Admin.
     */
    private void authorizeForView(HttpSession session, String path, String stagingCuid) throws AuthorizationException {
        if (!path.endsWith(".css") && !path.endsWith(".js") && !path.endsWith(".jpg") && !path.endsWith(".png")
                && !path.endsWith(".gif") && !path.endsWith("woff") && !path.endsWith("woff2") && !path.endsWith("ttf")) {
            AuthenticatedUser user = (AuthenticatedUser) session.getAttribute("authenticatedUser");
            if (stagingCuid != null) {
                if (user == null)
                    throw new AuthorizationException(AuthorizationException.NOT_AUTHORIZED, "Authentication failure");
                if (!user.getCuid().equals(stagingCuid) && !user.hasRole(Workgroup.SITE_ADMIN_GROUP))
                    throw new AuthorizationException(AuthorizationException.NOT_AUTHORIZED, "User " + user.getCuid() + " not authorized");
            }
            if (UserGroupCache.getRole(Role.ASSET_VIEW) != null) {
                if (user == null)
                    throw new AuthorizationException(AuthorizationException.NOT_AUTHORIZED, "Authentication failure");
                if (!user.hasRole(Role.ASSET_VIEW) && !user.hasRole(Role.ASSET_DESIGN) && !user.hasRole(Workgroup.SITE_ADMIN_GROUP)) {
                    throw new AuthorizationException(AuthorizationException.FORBIDDEN,
                            "User " + user.getCuid() + " not authorized for " + path);
                }
            }
        }
    }
}
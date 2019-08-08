package com.centurylink.mdw.hub.servlet.asset;

import com.centurylink.mdw.common.service.ServiceException;
import com.centurylink.mdw.dataaccess.file.VersionControlGit;
import com.centurylink.mdw.model.asset.Asset;
import com.centurylink.mdw.services.ServiceLocator;
import com.centurylink.mdw.services.StagingServices;
import com.centurylink.mdw.services.cache.CacheRegistration;
import com.centurylink.mdw.util.file.Packages;
import com.centurylink.mdw.util.file.VersionProperties;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class StagedAssetUpdater {

    private static final StandardLogger logger = LoggerUtil.getStandardLogger();

    private HttpServletRequest servletRequest;

    public StagedAssetUpdater(HttpServletRequest servletRequest) {
        this.servletRequest = servletRequest;
    }

    public void updateAsset(String stagingCuid, String path) throws Exception {

        StagingServices stagingServices = ServiceLocator.getStagingServices();
        VersionControlGit vcs = stagingServices.getStagingVersionControl(stagingCuid);
        vcs.pull(vcs.getBranch());

        int slash = path.indexOf('/');
        if (slash == -1 || slash > path.length() - 2)
            throw new ServiceException(ServiceException.BAD_REQUEST, "Bad path: " + path);

        String pkgName = path.substring(0, slash);
        String assetName = path.substring(slash + 1);

        File assetRoot = stagingServices.getStagingAssetsDir(stagingCuid);
        File pkgDir = new File(assetRoot.getPath() + "/" + pkgName.replace('.', '/'));
        File assetFile = new File(pkgDir.getPath() + "/" + assetName);
        if (!assetFile.exists())
            throw new ServiceException(ServiceException.NOT_FOUND, "Not found: " + path);

        File versionsFile = new File(pkgDir.getPath() + "/" + Packages.META_DIR + "/" + Packages.VERSIONS);
        VersionProperties versionProps = new VersionProperties(versionsFile);
        String versionProp = versionProps.getProperty(assetName);
        if (versionProp == null)
            versionProp = "0";
        int currentVersion = Integer.parseInt(versionProp);

        // version is always incremented since every saved change for a staging area is pushed
        int newVersion = currentVersion + 1;
        versionProps.setProperty(assetName, String.valueOf(newVersion));

        logger.info("Saving asset: " + pkgName + "/" + assetName + " v" + Asset.formatVersion(newVersion));

        InputStream is = servletRequest.getInputStream();
        try (FileOutputStream fos = new FileOutputStream(assetFile)) {
            int read;
            byte[] bytes = new byte[1024];
            while ((read = is.read(bytes)) != -1)
                fos.write(bytes, 0, read);
        }

        versionProps.save();

        logger.info("Asset saved: " + path + " v" + Asset.formatVersion(newVersion));

        new Thread(() -> {
            CacheRegistration.getInstance().refreshCaches(null);
        }).start();
    }
}

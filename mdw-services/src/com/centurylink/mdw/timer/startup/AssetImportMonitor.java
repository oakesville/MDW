package com.centurylink.mdw.timer.startup;

import com.centurylink.mdw.app.ApplicationContext;
import com.centurylink.mdw.cli.Import;
import com.centurylink.mdw.cli.Props;
import com.centurylink.mdw.common.service.SystemMessages;
import com.centurylink.mdw.config.PropertyManager;
import com.centurylink.mdw.constant.PropertyNames;
import com.centurylink.mdw.dataaccess.DbAccess;
import com.centurylink.mdw.git.VersionControlGit;
import com.centurylink.mdw.model.system.Bulletin;
import com.centurylink.mdw.model.system.SystemMessage.Level;
import com.centurylink.mdw.services.AssetServices;
import com.centurylink.mdw.services.ServiceLocator;
import com.centurylink.mdw.services.cache.CacheRegistration;
import com.centurylink.mdw.startup.StartupException;
import com.centurylink.mdw.startup.StartupService;
import com.centurylink.mdw.util.log.LoggerUtil;
import com.centurylink.mdw.util.log.StandardLogger;
import org.eclipse.jgit.api.errors.CheckoutConflictException;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

/**
 * Checks for asset imports performed on other instances
 */
public class AssetImportMonitor implements StartupService {

    private static StandardLogger logger = LoggerUtil.getStandardLogger();

    private static boolean _terminating;
    private static AssetImportMonitor monitor = null;
    private static Thread thread = null;
    private static boolean gitHardResetOverride = false;
    /**
     * Invoked when the server starts up.
     */
    public void onStartup() throws StartupException {
        boolean enabled = PropertyManager.getBooleanProperty(PropertyNames.MDW_ASSET_SYNC_ENABLED,
                !ApplicationContext.isDevelopment());
        if (monitor == null) {
            monitor = this;
            if (enabled) {
                thread = new Thread() {
                    public void run() {
                        this.setName("AssetImportMonitor-thread");
                        monitor.start();
                    }
                };
                thread.start();
            }
            else {
                logger.info("Asset Import Monitor disabled");
            }
        }
    }

    public void onShutdown() {
        _terminating = true;
        if (thread != null)
            thread.interrupt();
    }

    public void start() {
        Bulletin bulletin = null;
        try {
            long interval = PropertyManager.getLongProperty(PropertyNames.MDW_ASSET_SYNC_INTERVAL, 60000); //Defaults to checking every 60 seconds
            boolean gitHardReset = PropertyManager.getBooleanProperty(PropertyNames.MDW_ASSET_SYNC_GITRESET, false);
            boolean gitAutoPull = PropertyManager.getBooleanProperty(PropertyNames.MDW_GIT_AUTO_PULL, false);
            File assetDir = PropertyManager.getProperty(PropertyNames.MDW_ASSET_LOCATION) == null ? null : new File(PropertyManager.getProperty(PropertyNames.MDW_ASSET_LOCATION));
            String user = PropertyManager.getProperty(PropertyNames.MDW_GIT_USER);
            String branch = PropertyManager.getProperty(PropertyNames.MDW_GIT_BRANCH);
            File gitRoot = PropertyManager.getProperty(PropertyNames.MDW_GIT_LOCAL_PATH) == null ? null : new File(PropertyManager.getProperty(PropertyNames.MDW_GIT_LOCAL_PATH));
            AssetServices assetServices = ServiceLocator.getAssetServices();
            VersionControlGit vcs = (VersionControlGit)assetServices.getVersionControl();
            if (vcs == null || vcs.getCommit() == null || assetDir == null || gitRoot == null || branch == null || user ==  null) {
                _terminating = true;
                String message = "Failed to start Asset Import Monitor due to: ";
                message += assetDir == null ? "Missing mdw.asset.location property" : "";
                message += gitRoot == null ? message.length() > 0 ? ", missing mdw.git.local.path property" : "Missing mdw.git.local.path property" : "";
                message += branch == null ? message.length() > 0 ? ", missing mdw.git.branch property" : "Missing mdw.git.branch property" : "";
                message += user == null ? message.length() > 0 ? ", missing mdw.git.user property" : "Missing mdw.git.user property" : "";
                message += (vcs == null || vcs.getCommit() == null) ? message.length() > 0 ? ", missing Git repository" : "missing Git repository" : "";
                logger.warn(message);
            }
            else {
                _terminating = false;
            }

            while (!_terminating) {
                try {
                    Thread.sleep(interval);

                    // Check if it needs to trigger an asset import to sync up this instance's assets
                    String select = "select value from VALUE where name= ? and owner_type= ? and owner_id= ?";
                    try (DbAccess dbAccess = new DbAccess(); PreparedStatement stmt = dbAccess.getConnection().prepareStatement(select)) {
                        // Always trigger import if there's a newer remote commit or branch switching and auto pull is enabled
                        if (gitAutoPull && (!branch.equals(vcs.getBranch()) || vcs.getCommitTime(vcs.getCommit()) < vcs.getCommitTime(vcs.getRemoteCommit(branch)))) {
                            if (!branch.equals(vcs.getBranch()))
                                logger.info("Switching to branch " + branch + " with Git Auto Pull ENABLED.  Performing Asset Import...");
                            else
                                logger.info("New commit found on remote branch " + branch + " with Git Auto Pull ENABLED.  Performing Asset Import...");

                            performImport(gitRoot, assetDir, vcs, branch, gitHardReset, dbAccess.getConnection());
                        }
                        // Git Auto Pull is not enabled
                        else if (!gitAutoPull) {
                            // If head commit in local repo is newer than commit from VALUE DB table, update VALUE DB table entry (means new local commit - asset saved, or new instance spun up)
                            stmt.setString(1, "CommitID");
                            stmt.setString(2, "AssetImport");
                            stmt.setString(3, "0");
                            String latestImportCommit = null;
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) {
                                    latestImportCommit = rs.getString("value");
                                }
                            }

                            // If no row found in DB, create it
                            if (latestImportCommit == null) {
                                String insert = "insert into VALUE (value, name, owner_type, owner_id, create_dt, create_usr, mod_dt, mod_usr, comments) "
                                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                                Timestamp currentDate = new Timestamp(System.currentTimeMillis());
                                try (PreparedStatement insertStmt = dbAccess.getConnection().prepareStatement(insert)) {
                                    insertStmt.setString(1, vcs.getCommit());
                                    insertStmt.setString(2, "CommitID");
                                    insertStmt.setString(3, "AssetImport");
                                    insertStmt.setString(4, "0");
                                    insertStmt.setTimestamp(5, currentDate);
                                    insertStmt.setString(6, "MDWEngine");
                                    insertStmt.setTimestamp(7, currentDate);
                                    insertStmt.setString(8, "MDWEngine");
                                    insertStmt.setString(9, "Represents the last time assets were imported");
                                    insertStmt.executeUpdate();
                                }
                            }
                            // Proceed if latest commit from VALUE table doesn't match current local Git commit (Potential import done in other instance)
                            else if (!latestImportCommit.equals(vcs.getCommit())) {
                                vcs.fetch();  // Do a fetch so we know about newer commits since instance last started
                                long localCommitTime = vcs.getCommitTime(vcs.getCommit());
                                long lastImportTime = vcs.getCommitTime(latestImportCommit);
                                // Check if import commit is newer than the current local commit - Otherwise, means new local commit - asset saved, or new instance spun up
                                if (localCommitTime < lastImportTime) {
                                    logger.info("Detected Asset Import in cluster.  Performing Asset Import...");
                                    performImport(gitRoot, assetDir, vcs, branch, gitHardReset, dbAccess.getConnection());
                                } else {  // Update VALUE DB entry to trigger import on other instances to this newer commit
                                    String update = "update VALUE set value = ?, mod_dt = ? where name = ? and owner_type = ? and owner_id = ?";
                                    try (PreparedStatement updateStmt = dbAccess.getConnection().prepareStatement(update)) {
                                        updateStmt.setString(1, vcs.getCommit());
                                        updateStmt.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                                        updateStmt.setString(3, "CommitID");
                                        updateStmt.setString(4, "AssetImport");
                                        updateStmt.setString(5, "0");
                                        updateStmt.executeUpdate();
                                    }
                                }
                            }
                        }
                    }
                }
                catch (InterruptedException e) {
                    if (!_terminating) throw e;
                    logger.info(this.getClass().getName() + " stopping.");
                }
            }
        }
        catch (Exception e) {
            logger.error(e.getMessage(), e);
            SystemMessages.bulletinOff(bulletin, Level.Error, "Asset import failed: " + ((e.getMessage() == null && e.getCause() != null) ? e.getCause().getMessage() : e.getMessage()));
            // Check if we need to try next time with a hard reset
            Throwable ex = e instanceof IOException && e.getCause() != null ? e.getCause() : e;
            if (ex instanceof CheckoutConflictException)
                gitHardResetOverride = true;   // Next import attempt will try git hard reset
        }
        finally {
            if (!_terminating) this.start();  // Restart if a failure occurred, besides instance is shutting down
        }
    }

    private void performImport(File gitRoot, File assetDir, VersionControlGit vcs, String branch, boolean gitHardReset, Connection conn) throws IOException {
        Bulletin bulletin = SystemMessages.bulletinOn("Asset import in progress...");
        Import importer = new Import(gitRoot, vcs, branch, gitHardResetOverride ? gitHardResetOverride : gitHardReset, conn);
        Props.init("mdw.yaml");
        importer.setAssetLoc(assetDir.getPath());
        importer.setConfigLoc(PropertyManager.getConfigLocation());
        importer.setGitRoot(gitRoot);
        importer.importAssetsFromGit();
        SystemMessages.bulletinOff(bulletin, "Asset import completed");
        gitHardResetOverride = false;   // Import successful, so reset back to use gitHardReset property
        bulletin = null;
        CacheRegistration.getInstance().refreshCaches();
    }
}

package com.centurylink.mdw.model.asset.api;

import com.centurylink.mdw.model.Jsonable;
import com.centurylink.mdw.model.system.Server;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.List;

@ApiModel(value="PackageList", description="Asset package list")
public class PackageList implements Jsonable {

    private Server server;
    public Server getServer() { return server; }

    private File assetRoot;
    @ApiModelProperty(dataType="string")
    public File getAssetRoot() { return assetRoot; }

    private File vcsRoot;
    @ApiModelProperty(dataType="string")
    public File getVcsRoot() { return vcsRoot; }

    private String vcsBranch;
    public String getVcsBranch() { return vcsBranch; }
    public void setVcsBranch(String branch) { this.vcsBranch = branch; }

    private String vcsTag;
    public String getVcsTag() { return vcsTag; }
    public void setVcsTag(String tag) { this.vcsTag = tag; }

    /**
     * In case different from vcsBranch (switch scenario).
     */
    private String gitBranch;
    public String getGitBranch() { return gitBranch; }
    public void setGitBranch(String branch) { this.gitBranch = branch; }

    private String vcsRemoteUrl;
    public String getVcsRemoteUrl() { return vcsRemoteUrl; }
    public void setVcsRemoteUrl(String url) { this.vcsRemoteUrl = url; }

    private List<PackageInfo> packageInfos;
    public List<PackageInfo> getPackageInfos() { return packageInfos; }
    public void setPackageInfos(List<PackageInfo> pkgInfos) { this.packageInfos = pkgInfos; }

    public PackageList(Server server, File assetRoot, File vcsRoot) {
        this.server = server;
        this.assetRoot = assetRoot;
        this.vcsRoot = vcsRoot;
    }

    public JSONObject getJson() throws JSONException {
        JSONObject pkgs = create();
        pkgs.put("serverInstance", server.toString());
        pkgs.put("assetRoot", assetRoot.toString().replace('\\', '/'));
        if (vcsRoot != null)
            pkgs.put("vcsRoot", vcsRoot);
        if (vcsBranch != null)
            pkgs.put("vcsBranch", vcsBranch);
        if (vcsTag != null)
            pkgs.put("vcsTag", vcsTag);
        if (gitBranch != null)
            pkgs.put("gitBranch", gitBranch);
        if (vcsRemoteUrl != null)
            pkgs.put("vcsRemoteUrl", vcsRemoteUrl);
        JSONArray pkgArray = new JSONArray();
        if (packageInfos != null) {
            for (PackageInfo packageInfo : packageInfos) {
                pkgArray.put(packageInfo.getJson());
            }
        }
        pkgs.put("packages", pkgArray);
        return pkgs;
    }

    public String getJsonName() {
        return "Packages";
    }

    public void sort() {
        if (packageInfos != null)
            Collections.sort(packageInfos);
    }
}

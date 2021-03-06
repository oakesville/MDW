package com.centurylink.mdw.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;

@Parameters(commandNames="init", commandDescription="Initialize an MDW project", separators="=")
public class Init extends Setup {

    /**
     * Existing project directory is okay (mdw-studio wizard).
     */
    public Init(File projectDir) {
        super(projectDir);
        project = projectDir.getName();
        overwrite = true;
    }

    Init() {
        // cli use only
    }

    @Parameter(description="<project>", required=true)
    private String project;

    @Parameter(names="--mdw-version", description="MDW Version")
    private String mdwVersion;
    @Override
    public String getMdwVersion() throws IOException { return mdwVersion; }
    public void setMdwVersion(String version) { this.mdwVersion = version; }

    @Parameter(names="--overwrite", description="Overwrite existing")
    private boolean overwrite;
    public boolean isOverwrite() { return overwrite; }
    public void setOverwrite(boolean overwrite) { this.overwrite = overwrite; }

    @Parameter(names="--snapshots", description="Whether to include snapshot builds")
    private boolean snapshots;
    public boolean isSnapshots() throws IOException { return snapshots; }
    public void setSnapshots(boolean snapshots) { this.snapshots = snapshots; }

    @Parameter(names="--user", description="Dev user")
    private String user = System.getProperty("user.name");
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    @Parameter(names="--eclipse", description="Generate Eclipse workspace artifacts")
    private boolean eclipse;
    public boolean isEclipse() { return eclipse; }
    public void setEclipse(boolean eclipse) { this.eclipse = eclipse; }

    @Parameter(names="--maven", description="Generate a Maven pom.xml build file")
    private boolean maven;
    public boolean isMaven() { return maven; }
    public void setMaven(boolean maven) { this.maven = maven; }

    @Parameter(names="--spring-boot", description="Spring Boot artifact generation")
    private boolean springBoot;
    public boolean isSpringBoot() { return springBoot; }
    public void setSpringBoot(boolean springBoot) { this.springBoot = springBoot; }

    @Parameter(names="--no-update", description="Suppress base asset download")
    private boolean noUpdate;
    public boolean isNoUpdate() { return noUpdate; }
    public void setNoUpdate(boolean noUpdate) { this.noUpdate = noUpdate; }

    @Override
    public File getProjectDir() {
        return projectDir == null ? new File(project) : projectDir;
    }

    public Init run(ProgressMonitor... progressMonitors) throws IOException {
        getOut().println("Initializing " + project + "...");
        int slashIndex = project.lastIndexOf('/');
        if (slashIndex > 0)
            project = project.substring(slashIndex + 1);

        if (getProjectDir().exists() && !overwrite) {
            if (!getProjectDir().isDirectory() || getProjectDir().list().length > 0) {
                getErr().println(getProjectDir() + " already exists and is not an empty directory");
                return this;
            }
        }
        else {
            if (!getProjectDir().isDirectory() && !getProjectDir().mkdirs())
                throw new IOException("Unable to create project dir: " + getProjectDir());
        }

        if (mdwVersion == null)
            mdwVersion = findMdwVersion(isSnapshots());
        if (configLoc == null)
            configLoc = "config";
        if (assetLoc == null)
            assetLoc = "assets";
        if (sourceGroup == null)
            sourceGroup = "com.example." + getProjectDir().getName();

        File tempZip = Files.createTempFile("mdw-templates", ".zip").toFile();
        tempZip.deleteOnExit();
        if (templateDir == null) {
            String templatesUrl = getTemplatesUrl();
            getOut().println("Retrieving templates: " + templatesUrl);
            new Download(new URL(templatesUrl), tempZip).run(progressMonitors);
        }
        else {
            getOut().println("Using templates from: " + templateDir);
            new Zip(new File(templateDir), tempZip).run(progressMonitors);
        }
        new Unzip(tempZip, getProjectDir(), overwrite, opt -> {
            Object value = getValue(opt);
            return value == null ? false : Boolean.valueOf(value.toString());
        }).run();
        deleteDynamicTemplates();
        getOut().println("Writing: ");
        subst(getProjectDir());
        if (isSnapshots())
            updateBuildFile();
        new File(getProjectDir() + "/src/main/java").mkdirs();
        if (!isNoUpdate()) {
            Update update = new Update(getProjectDir());
            if (!new File(getAssetLoc()).isAbsolute())
                update.setAssetLoc(getProjectDir().getName() + "/" + getAssetLoc());
            update.run(progressMonitors);
        }
        if (isMaven()) {
            File buildGradle = new File(getProjectDir() + "/build.gradle");
            if (buildGradle.exists())
                buildGradle.delete();
            File gradleProperties = new File(getProjectDir() + "/gradle.properties");
            if (gradleProperties.exists())
                gradleProperties.delete();
        }
        else {
            File pomXml = new File(getProjectDir() + "/pom.xml");
            if (pomXml.exists())
                pomXml.delete();
        }
        return this;
    }

    /**
     * These will be retrieved just-in-time based on current mdw version.
     */
    private void deleteDynamicTemplates() throws IOException {
        File codegenDir = new File(getProjectDir() + "/codegen");
        if (codegenDir.exists()) {
            getOut().println("Deleting " + codegenDir);
            new Delete(codegenDir, true).run();
        }
        File assetsDir = new File(getProjectDir() + "/assets");
        if (assetsDir.exists()) {
            getOut().println("Deleting " + assetsDir);
            new Delete(assetsDir, true).run();
        }
        File configuratorDir = new File(getProjectDir() + "/configurator");
        if (configuratorDir.exists()) {
            getOut().println("Deleting " + configuratorDir);
            new Delete(configuratorDir, true).run();
        }
    }

    protected boolean needsConfig() { return false; }
}

package com.centurylink.mdw.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.IParameterSplitter;

@Parameters(commandNames="run", commandDescription="Run the MDW server", separators="=")
public class Run implements Operation {

    protected static List<String> defaultVmArgs = new ArrayList<>();
    static {
        defaultVmArgs.add("-Dmdw.runtime.env=dev");
        defaultVmArgs.add("-Dmdw.config.location=config");
    }

    private File projectDir;
    public File getProjectDir() { return projectDir; }

    Run() {
        // cli use only
        this.projectDir = new File(".");
    }
    public Run(File projectDir) {
        this.projectDir = projectDir;
    }

    @Parameter(names="--binaries-url", description="MDW Binaries")
    private String binariesUrl = "https://github.com/CenturyLinkCloud/mdw/releases";
    public String getBinariesUrl() { return binariesUrl; }
    public void setBinariesUrl(String url) { this.binariesUrl = url; }

    @Parameter(names="--vm-args", description="Java VM Arguments (enclose in quotes)",
            splitter=SpaceParameterSplitter.class)
    private List<String> vmArgs = defaultVmArgs;
    public List<String> getVmArgs() { return vmArgs; }
    public void setVmArgs(List<String> args) { this.vmArgs = args; }

    @Parameter(names="--daemon", description="Spawn as a background process")
    private boolean daemon;
    public boolean isDaemon() { return daemon; }
    public void setDaemon(boolean daemon) { this.daemon = daemon; }

    @Parameter(names="--wait", description="(--daemon mode only) If specified, startup will await service availability for up to this many seconds")
    private int wait;
    public int getWait() { return wait; }
    public void setWait(int timeout) { this.wait = timeout; }

    /**
     * Retries may be necessary because of Gradle flakiness under Docker (i.e. travis-ci).
     */
    @Parameter(names="--retries", description="(--daemon mode with --wait) If specified, number of times to retry daemon startup when not becoming available")
    private int retries;
    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }
    private int retried;

    @Parameter(names="--boot-jar-loc", description="MDW Boot Jar Location")
    private String bootJarLoc = ".";
    public String getBootJarLoc() { return bootJarLoc; }
    public void setBootJarLoc(String loc) { this.bootJarLoc = loc; }

    public Run run(ProgressMonitor... progressMonitors) throws IOException {
        List<String> cmdLine = new ArrayList<>();
        if (daemon) {
            if (System.getProperty("os.name").startsWith("Windows")) {
                cmdLine.add(System.getenv("MDW_HOME") + "\\bin\\mdwd.bat");
            }
            else {
                cmdLine.add(System.getenv("MDW_HOME") + "/bin/mdwd");
            }
            cmdLine.addAll(getVmArgs());
            File logDir = getLogDir();
            if (logDir == null) {
                logDir = new File(".");
                cmdLine.add("-Dmdw.log.location=.");
            }
            getOut().println("Running MDW daemon with log dir: " + logDir.getAbsolutePath());
            cmdLine.add(getBootJar());
        }
        else {
            cmdLine.add(getJava());
            cmdLine.add("-jar");
            cmdLine.addAll(getVmArgs());
            cmdLine.add(getBootJar());
        }
        ProcessBuilder builder = new ProcessBuilder(cmdLine);
        builder.redirectErrorStream(true);
        getOut().println("Starting process:");
        for (String cmd : cmdLine)
            getOut().print(cmd + " ");
        getOut().println("\n");
        Process process = builder.start();
        if (!daemon) {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run() {
                    try {
                        new Stop().run();
                        process.waitFor();
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                        process.destroy();
                    }
                }
            });
        }

        if (!daemon || !System.getProperty("os.name").startsWith("Windows")) {  // this blocks on windows
            new Thread(new Runnable() {
                public void run() {
                    try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream()));) {
                        out.lines().forEach(line -> {
                            getOut().println(line);
                        });
                    }
                    catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }
            }).start();
        }

        try {
            int res = process.waitFor();
            getOut().println("Process exited with code: " + res);
            if (daemon && wait > 0) {
                long before = System.currentTimeMillis();
                boolean available = false;
                URL url = new URL("http://localhost:" + getServerPort() + "/" + getContextRoot() + "/services/AppSummary");
                getOut().println("Awaiting mdw services availability at " + url);
                while (!available) {
                    long elapsed = System.currentTimeMillis() - before;
                    if (elapsed / 1000 > wait) {
                        getOut().println("\nStartup timeout (" + wait + " s) exceeded");
                        if (retried < retries) {
                            retried++;
                            process.destroy();
                            getOut().println("   Retrying (" + retried + ")");
                            run(progressMonitors);
                            return this;
                        }
                        else {
                            throw new InterruptedException();
                        }
                    }
                    try {
                        String response = new Fetch(url).run().getData();
                        String mdwVersion = new JSONObject(response).getString("mdwVersion");
                        getOut().println("\nMDW " + mdwVersion + " became available after " + (elapsed/1000) + " s");
                        available = true;
                    }
                    catch (IOException | JSONException ex) {
                        // unparseable means not available
                        getOut().print("...");
                        Thread.sleep(3000);
                    }
                }
            }
        }
        catch (InterruptedException ex) {
            getOut().println("Destroying process");
            process.destroy();
        }

        return this;
    }

    protected String getJava() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }

    protected String getBootJar() throws IOException {
        String bootJarLoc = getBootJarLoc();
        if (bootJarLoc.endsWith(".jar")) {
            return bootJarLoc;
        }
        else {
            String mdwVersion = new Version().getMdwVersion(getProjectDir());
            return bootJarLoc + File.separator + "mdw-boot-" + mdwVersion + ".jar";
        }
    }

    protected File getLogDir() {
        for (String arg : getVmArgs()) {
            if (arg.startsWith("-Dmdw.logging.dir="))
                return new File(arg.substring(18));
        }
        return null;
    }

    static class SpaceParameterSplitter implements IParameterSplitter {
        @Override
        public List<String> split(String value) {
            return Arrays.asList(value.split(" "));
        }
    }

    protected int getServerPort() {
        for (String arg : getVmArgs()) {
            if (arg.startsWith("-Dmdw.server.port=") || arg.equals("-Dserver.port=")) {
                return Integer.parseInt(arg.substring(arg.indexOf('=') + 1));
            }
        }
        return 8080;
    }

    protected String getContextRoot() {
        for (String arg : getVmArgs()) {
            if (arg.startsWith("-Dmdw.server.contextPath=") || arg.equals("-Dserver.contextPath=")) {
                return arg.substring(arg.indexOf('=') + 1);
            }
        }
        return "mdw";
    }
}

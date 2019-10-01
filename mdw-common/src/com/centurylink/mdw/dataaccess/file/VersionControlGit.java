/*
 * Copyright (C) 2017 CenturyLink, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.centurylink.mdw.dataaccess.file;

import com.centurylink.mdw.dataaccess.AssetRevision;
import com.centurylink.mdw.dataaccess.VersionControl;
import com.centurylink.mdw.dataaccess.file.GitDiffs.DiffType;
import com.centurylink.mdw.model.asset.CommitInfo;
import com.centurylink.mdw.util.file.VersionProperties;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.pgm.Main;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class VersionControlGit implements VersionControl {

    public static final String VERSIONS_FILE = ".mdw/versions";

    /**
     * should be sufficient, but we could change to 8 or more
     * (com.centurylink.mdw.abbreviated.id.length system property)
     */
    private String repositoryUrl;

    private File localDir;
    public File getLocalDir() { return localDir; }

    private Repository localRepo;
    private Git git;

    private Map<File,Long> file2id;
    private Map<Long,File> id2file;

    private Map<File,VersionProperties> pkg2versions;

    private CredentialsProvider credentialsProvider;
    public void setCredentialsProvider(CredentialsProvider provider) { this.credentialsProvider = provider; }

    public VersionControlGit() {
    }

    public void connect(String repositoryUrl, String user, String password, File localDir) throws IOException {

        if (repositoryUrl != null && !repositoryUrl.isEmpty()) {
            if (user == null) {
                this.repositoryUrl = repositoryUrl;
            }
            else {
                int slashSlash = repositoryUrl.indexOf("//");
                this.repositoryUrl = repositoryUrl.substring(0, slashSlash + 2) + user + ":" + password + "@" + repositoryUrl.substring(slashSlash + 2);
            }
        }

        this.localDir = localDir;
        localRepo = new FileRepository(new File(localDir + "/.git"));
        git = new Git(localRepo);
        if (credentialsProvider == null) {
            if (user != null && password != null)
              credentialsProvider = new UsernamePasswordCredentialsProvider(user, password);
        }

        file2id = new HashMap<>();
        id2file = new HashMap<>();
        pkg2versions = new HashMap<>();
    }

    public synchronized void reconnect() throws IOException {
        Repository newLocalRepo = new FileRepository(new File(localDir + "/.git"));
        git = new Git(newLocalRepo);
        localRepo.close();
        localRepo = newLocalRepo;
    }

    public String toString() {
        return localDir + "->" + repositoryUrl;
    }

    /**
     * Cannot use git object hash since identical files would return duplicate
     * ids. Hash using git algorithm based on the relative file path.
     */
    public long getId(File file) throws IOException {
        Long id = file2id.get(file);
        if (id == null) {
            id = gitHash(file);
            file2id.put(file, id);
            id2file.put(id, file);
        }
        return id;
    }

    /**
     * This produces the same hash for a given object that Git 'hash-object' creates
     */
    public String getGitId(File input) throws IOException {
        String hash = "";
        if (input.isFile()) {
            FileInputStream fis = null;
            try {
                int fileSize = (int)input.length();
                fis = new FileInputStream(input);
                byte[] fileBytes = new byte[fileSize];
                fis.read(fileBytes);
                //hash = localRepo.newObjectInserter().idFor(Constants.OBJ_BLOB, fileBytes).getName(); // This is slower than below code (even if reusing ObjectInserter instance)
                String blob = "blob " + fileSize + "\0" + new String(fileBytes);
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] bytes = md.digest(blob.getBytes());
                hash = byteArrayToHexString(bytes);
            }
            catch (Throwable ex) {
                throw new IOException(ex.getMessage(), ex);
            }
            finally {
                if (fis != null)
                    fis.close();
            }
        }
        return hash;
    }

    public File getFile(long id) {
        return id2file.get(id);
    }

    public void clearId(File file) {
        Long id = file2id.remove(file);
        if (id != null)
            id2file.remove(id);
    }

    public void clear() {
        file2id.clear();
        id2file.clear();
        pkg2versions.clear();
    }

    public void deleteRev(File file) throws IOException {
        VersionProperties verProps = getVersionProps(file.getParentFile());
        if (verProps != null){
            String val = verProps.getProperty(file.getName());
            if (val != null) {
                verProps.remove(file.getName());
                verProps.save();
            }
        }
    }

    public AssetRevision getRevision(File file) throws IOException {
        return getRevisionInVersionsFile(file);
    }

    public void setRevision(File file, AssetRevision rev) throws IOException {
        setRevisionInVersionsFile(file, rev);
    }

    public AssetRevision getRevisionInVersionsFile(File file) throws IOException {
        Properties verProps = getVersionProps(file.getParentFile());
        if (verProps == null)
            return null;
        String propVal = verProps.getProperty(file.getName());
        if (propVal == null) {
            return null;
        }
        else {
            return parseAssetRevision(propVal.trim());
        }
    }

    public void setRevisionInVersionsFile(File file, AssetRevision rev) throws IOException {
        VersionProperties verProps = getVersionProps(file.getParentFile());
        if (verProps == null) {
            // presumably newly-created package
            File verFile = new File(file.getParentFile() + "/" + VERSIONS_FILE);
            Files.write(verFile.toPath(), "".getBytes(), StandardOpenOption.CREATE_NEW);
            verProps = new VersionProperties(verFile);
            pkg2versions.put(file.getParentFile(), verProps);
        }
        verProps.setProperty(file.getName(), String.valueOf(rev.getVersion()));
        verProps.save();
    }

    public static AssetRevision parseAssetRevision(String propertyValue) {
        AssetRevision assetRevision = new AssetRevision();
        int firstSpace = propertyValue.indexOf(' ');
        if (firstSpace > 0) {
            // includes comment
            assetRevision.setVersion(Integer.parseInt(propertyValue.substring(0, firstSpace)));
        }
        else {
            assetRevision.setVersion(Integer.parseInt(propertyValue));
        }
        return assetRevision;
    }

    private VersionProperties getVersionProps(File pkgDir) throws IOException {
        VersionProperties props = pkg2versions.get(pkgDir);
        if (props == null) {
            File file = new File(pkgDir + "/" + VERSIONS_FILE);
            if (file.exists()) {
                props = new VersionProperties(file);
                pkg2versions.put(pkgDir, props);
            }
            else if (!file.getAbsolutePath().contains(localDir.getAbsolutePath())) {
                return getVersionProps(new File(localDir.getAbsolutePath() + "/" + pkgDir.getPath()));
            }
        }
        return props;
    }

    /**
     * Use git hashing algorithm.
     * http://stackoverflow.com/questions/7225313/how-does-git-compute-file-hashes
     */
    public Long gitHash(File input) throws IOException {
        String path = getLogicalPath(input);
        String blob = "blob " + path.length() + "\0" + path;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(blob.getBytes());
            String h = byteArrayToHexString(bytes).substring(0, 7);
            return Long.parseLong(h, 16);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
    }

    private String getLogicalPath(File file) {
        return file.getPath().replace('\\', '/');
    }

    public String getCommit() throws IOException {
        ObjectId head = localRepo.resolve(Constants.HEAD);
        if (head != null)
            return head.getName();
        else
            return null;
    }

    public String getCommitForTag(String tag) throws Exception {
        fetch();
        List<Ref> tagRefs = git.tagList().call();
        for (Ref tagRef : tagRefs) {
            if (tagRef.getName().equals("refs/tags/" + tag))
                return tagRef.getObjectId().name();
        }

        return null;
    }

    /**
     * Get remote HEAD commit.
     */
    public String getRemoteCommit(String branch) throws Exception {
        fetch();
        ObjectId commit = localRepo.resolve("origin/" + branch);
        if (commit != null)
            return commit.getName();
        else
            return null;
    }

    public long getCommitTime(String commitId) throws Exception {
        try (RevWalk revWalk = new RevWalk(localRepo)) {
            return revWalk.parseCommit(ObjectId.fromString(commitId)).getCommitterIdent().getWhen().getTime();
        }
    }

    public String getBranch() throws IOException {
        return localRepo.getBranch();
    }

    public List<GitBranch> getRemoteBranches() throws Exception {
        fetch();
        List<Ref> refs = new Git(localRepo).branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call();
        List<GitBranch> branches = new ArrayList<>();
        for (Ref ref : refs) {
            String name = ref.getName();
            if (name.startsWith("refs/remotes/origin/"))
                name = name.substring(20);
            branches.add(new GitBranch(ObjectId.toString(ref.getObjectId()), name));
        }
        return branches;
    }

    /**
     * Does not do anything if already on target branch.
     */
    public void checkout(String branch) throws Exception {
        if (!branch.equals(getBranch())) {
            fetch(); // in case the branch is not known locally
            createBranchIfNeeded(branch);
            CheckoutCommand checkout = git.checkout().setName(branch)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK);
            checkout.setStartPoint("origin/" + branch);
            checkout.call();
            // for some reason jgit needs this when branch is switched
            git.checkout().setName(branch).call();
        }
    }

    public void checkoutTag(String tag) throws Exception {
        if (localRepo.getTags().get(tag) != null)
            git.checkout().setName(localRepo.getTags().get(tag).getName()).call();
    }

    public void hardCheckout(String branch) throws Exception {
        hardCheckout(branch, false);
    }

    public void hardTagCheckout(String tag) throws Exception {
        hardTagCheckout(tag, false);
    }

    /**
     * Performs a HARD reset and FORCED checkout.
     * Only to be used on server (not Designer).
     * FIXME: path is ignored
     */
    public void hardCheckout(String branch, Boolean hard) throws Exception {
        fetch(); // in case the branch is not known locally
        if (hard)
            hardReset();
        checkout(branch);
        pull(branch);  // pull before delete or next pull may add non-path items back
    }

    public void hardTagCheckout(String tag, Boolean hard) throws Exception {
        fetch(); // in case the tag is not known locally
        if (hard)
            hardReset();
        checkoutTag(tag);
    }

    public void hardReset() throws Exception {
        git.reset().setMode(ResetType.HARD).call();
        git.clean().call();
    }

    /**
     * Create a local branch for remote tracking if it doesn't exist already.
     */
    protected void createBranchIfNeeded(String branch) throws Exception {
        if (localRepo.findRef(branch) == null) {
            git.branchCreate()
               .setName(branch)
               .setUpstreamMode(SetupUpstreamMode.TRACK)
               .setStartPoint("origin/" + branch)
               .call();
        }
    }

    public void commit(String path, String msg) throws Exception {
        git.commit().setOnly(path).setMessage(msg).call();
    }

    public void commit(List<String> paths, String msg) throws Exception {
        CommitCommand commit = git.commit().setMessage(msg);
        for (String path : paths)
            commit.setOnly(path);
        commit.call();
    }

    public void commit(String msg) throws Exception {
        git.commit().setMessage(msg).call();
    }

    public void push() throws Exception {
        PushCommand push = git.push();
        if (credentialsProvider != null)
            push.setCredentialsProvider(credentialsProvider);
        push.call();
    }

    public void fetch() throws Exception {
        FetchCommand fetchCommand = git.fetch().setRemoveDeletedRefs(true);
        if (credentialsProvider != null)
            fetchCommand.setCredentialsProvider(credentialsProvider);
        try {
            fetchCommand.call();
        }
        catch (JGitInternalException | TransportException ex) {
            // LocalRepo object might be out of sync with actual local repo, so recreate objects for next time
            reconnect();
            throw ex;
        }
    }

    public void clone(String branch, ProgressMonitor progressMonitor) throws Exception {
        CloneCommand clone = Git.cloneRepository()
                .setURI(repositoryUrl)
                .setDirectory(localRepo.getDirectory().getParentFile());
        if (branch != null)
            clone.setBranch(branch);
        if (credentialsProvider != null)
            clone.setCredentialsProvider(credentialsProvider);
        if (progressMonitor != null)
            clone.setProgressMonitor(progressMonitor);
        clone.call();
    }

    public Status getStatus() throws Exception {
        return getStatus(null);
    }

    public Status getStatus(String path) throws Exception {
        fetch();
        StatusCommand sc = git.status();
        if (path != null)
            sc.addPath(path);
        return sc.call();
    }

    public void add(String path) throws Exception {
        git.add().addFilepattern(path).call();
    }

    public void add(List<String> paths) throws Exception {
        AddCommand add = git.add();
        for (String path : paths)
            add.addFilepattern(path);
        add.call();
    }

    public void rm(String path) throws Exception {
        git.rm().addFilepattern(path).call();
    }
    
    /**
     * In lieu of sparse checkout since it's not supported in JGit:
     * https://bugs.eclipse.org/bugs/show_bug.cgi?id=383772
     */
    public void cloneNoCheckout() throws Exception {
        CloneCommand clone = Git.cloneRepository().setURI(repositoryUrl).setDirectory(localRepo.getDirectory().getParentFile()).setNoCheckout(true);
        if (credentialsProvider != null)
            clone.setCredentialsProvider(credentialsProvider);
        clone.call();
    }

    public boolean localRepoExists() {
        return localRepo.getDirectory() != null && localRepo.getDirectory().exists();
    }

    public void pull(String branch) throws Exception {
        pull(branch, null);
    }

    public void pull(String branch, ProgressMonitor progressMonitor) throws Exception {
        PullCommand pull = git.pull().setRemote("origin").setRemoteBranchName(branch);
        if (credentialsProvider != null)
            pull.setCredentialsProvider(credentialsProvider);
        if (progressMonitor != null)
            pull.setProgressMonitor(progressMonitor);
        pull.call();
    }

    /**
     * Creates a new local branch and pushes to remote.
     */
    public void createBranch(String branch, String fromBranch, ProgressMonitor progressMonitor) throws Exception {
        if (localRepo.findRef(branch) == null) {
            CreateBranchCommand createBranch = git.branchCreate().setName(branch);
            if (fromBranch != null)
                createBranch.setStartPoint(fromBranch);
            createBranch.call();
        }
        PushCommand push = git.push();
        push.setRemote("origin");
        push.setRefSpecs(new RefSpec(branch));
        if (credentialsProvider != null)
            push.setCredentialsProvider(credentialsProvider);
        if (progressMonitor != null)
            push.setProgressMonitor(progressMonitor);
        push.call();
    }

    public static String byteArrayToHexString(byte[] b) {
        String result = "";
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < b.length; i++) {
            //noinspection StringConcatenationInLoop
            result += Integer.toString((b[i] & 0xff) + 0x100, 16).substring(1);
        }
        return result;
    }

    /**
     * @deprecated use {@link #getRelativePath(Path)}
     * this has issues with relative git local paths like ../
     */
    @Deprecated
    public String getRelativePath(File file) {
        String localPath = localDir.getAbsolutePath();
        if (localPath.endsWith("\\.") || localPath.endsWith("/."))
            localPath = localPath.substring(0, localPath.length() - 2);

        return file.getAbsolutePath().substring(localPath.length() + 1).replace('\\', '/');
    }

    @SuppressWarnings("unused")
    public String getRelativePath(Path path) {
        return localDir.toPath().normalize().relativize(path.normalize()).toString().replace('\\', '/');
    }

    public GitDiffs getDiffs(String branch, String path) throws Exception {
        fetch();
        ObjectId obj = localRepo.resolve("origin/" + branch + "^{tree}");
        if (obj == null)
            throw new IOException("Unable to determine Git Diffs: path " + path + " not found on branch " + branch);
        return getDiffs(obj, path);
    }

    @SuppressWarnings("unused")
    public GitDiffs getDiffsForTag(String tag, String path) throws Exception {
        fetch();
        ObjectId obj = localRepo.resolve("refs/tags/" + tag + "^{tree}");
        if (obj == null)
            throw new IOException("Unable to determine Git Diffs: path " + path + " not found on tag " + tag);
        return getDiffs(obj, path);
    }

    private GitDiffs getDiffs(ObjectId objectId, String path) throws Exception {
        GitDiffs diffs = new GitDiffs();
        CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
        newTreeIter.reset(localRepo.newObjectReader(), objectId);
        DiffCommand dc = git.diff().setNewTree(newTreeIter);
        if (path != null)
            dc.setPathFilter(PathFilter.create(path));
        dc.setShowNameAndStatusOnly(true);
        for (DiffEntry diff : dc.call()) {
            if (diff.getChangeType() == ChangeType.ADD || diff.getChangeType() == ChangeType.COPY) {
                diffs.add(DiffType.MISSING, diff.getNewPath());
            }
            else if (diff.getChangeType() == ChangeType.MODIFY) {
                diffs.add(DiffType.DIFFERENT, diff.getNewPath());
            }
            else if (diff.getChangeType() == ChangeType.DELETE) {
                diffs.add(DiffType.EXTRA, diff.getOldPath());
            }
            else if (diff.getChangeType() == ChangeType.RENAME) {
                diffs.add(DiffType.MISSING, diff.getNewPath());
                diffs.add(DiffType.EXTRA, diff.getOldPath());
            }
        }
        // we're purposely omitting folders
        Status status = git.status().addPath(path).call();
        for (String untracked : status.getUntracked()) {
            if (!untracked.startsWith(path + "/Archive/"))
                diffs.add(DiffType.EXTRA, untracked);
        }
        for (String added : status.getAdded()) {
            diffs.add(DiffType.EXTRA, added);
        }
        for (String missing : status.getMissing()) {
            diffs.add(DiffType.MISSING, missing);
        }
        for (String removed : status.getRemoved()) {
            diffs.add(DiffType.MISSING, removed);
        }
        for (String changed : status.getChanged()) {
            diffs.add(DiffType.DIFFERENT, changed);
        }
        for (String modified : status.getModified()) {
            diffs.add(DiffType.DIFFERENT, modified);
        }
        for (String conflict : status.getConflicting()) {
            diffs.add(DiffType.DIFFERENT, conflict);
        }
        return diffs;
    }

    /**
     * Does not fetch.
     */
    public CommitInfo getCommitInfo(String path) throws Exception {
        Iterator<RevCommit> revCommits = git.log().addPath(path).setMaxCount(1).call().iterator();
        if (revCommits.hasNext()) {
            return getCommitInfo(revCommits.next());
        }
        return null;
    }

    public CommitInfo getCommitInfoForRef(String ref) throws Exception {
        ObjectId commitId = ObjectId.fromString(ref);
        try (RevWalk revWalk = new RevWalk(localRepo)) {
            RevCommit revCommit = revWalk.parseCommit(commitId);
            if (revCommit != null) {
                return getCommitInfo(revCommit);
            }
        }
        return null;
    }

    private CommitInfo getCommitInfo(RevCommit revCommit) throws Exception {
        CommitInfo commitInfo = new CommitInfo(revCommit.getId().name());
        PersonIdent committerIdent = revCommit.getCommitterIdent();
        commitInfo.setCommitter(committerIdent.getName());
        commitInfo.setEmail(committerIdent.getEmailAddress());
        if ((commitInfo.getCommitter() == null || commitInfo.getCommitter().isEmpty()) && commitInfo.getEmail() != null)
            commitInfo.setCommitter(commitInfo.getEmail());
        commitInfo.setDate(committerIdent.getWhen());
        commitInfo.setMessage(revCommit.getShortMessage());
        return commitInfo;
    }

    public ObjectStream getRemoteContentStream(String branch, String path) throws Exception {
        ObjectId id = localRepo.resolve("refs/remotes/origin/" + branch);
        try (ObjectReader reader = localRepo.newObjectReader();
                RevWalk walk = new RevWalk(reader)) {
            RevCommit commit = walk.parseCommit(id);
            RevTree tree = commit.getTree();
            TreeWalk treewalk = TreeWalk.forPath(reader, path, tree);
            if (treewalk != null) {
                return reader.open(treewalk.getObjectId(0)).openStream();
            }
            else {
                return null;
            }
        }
    }

    public String getRemoteContentString(String branch, String path) throws Exception {
        InputStream in = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            in = getRemoteContentStream(branch, path);
            if (in == null)
                return null;
            int read;
            byte[] bytes = new byte[1024];
            while ((read = in.read(bytes)) != -1)
                out.write(bytes, 0, read);
        }
        finally {
            if (in != null)
                in.close();
        }
        return out.toString();
    }

    public boolean exists() {
        return new File(localDir + "/.git").isDirectory();
    }

    /**
     * Execute an arbitrary git command.
     */
    @SuppressWarnings("unused")
    public void git(String... args) throws Exception {
        Main.main(args);
    }

    public byte[] readFromCommit(String commitId, String path) throws Exception {
        try (RevWalk revWalk = new RevWalk(localRepo)) {
            RevCommit commit = revWalk.parseCommit(ObjectId.fromString(commitId));
            // use commit's tree find the path
            RevTree tree = commit.getTree();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (TreeWalk treeWalk = new TreeWalk(localRepo)) {
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilter.create(path));
                if (!treeWalk.next()) {
                    return null;
                }

                ObjectId objectId = treeWalk.getObjectId(0);
                ObjectLoader loader = localRepo.open(objectId);

                loader.copyTo(baos);
            }
            revWalk.dispose();
            return baos.toByteArray();
        }
    }

    @SuppressWarnings("unused")
    public byte[] readFromHead(String filePath) throws Exception {
        try {
            return readFromCommit(ObjectId.toString(localRepo.resolve(Constants.HEAD)), filePath);
        }
        catch (Exception e) {  // MDW Studio throws MissingObjectExceptions after a pull, on Windows at least
            reconnect();
            return readFromCommit(ObjectId.toString(localRepo.resolve(Constants.HEAD)), filePath);
        }
    }

    public void merge(String fromBranch, String toBranch) throws Exception {
        fetch();
        CheckoutCommand checkout = git.checkout().setName(toBranch).setCreateBranch(false);
        checkout.call();
        ObjectId fromId = localRepo.resolve("refs/remotes/origin/" + fromBranch);
        MergeCommand merge = git.merge().include(fromId);
        MergeResult mergeResult = merge.call();
        if (mergeResult.getMergeStatus().equals(MergeResult.MergeStatus.CONFLICTING)){
            throw new IOException("Merge conflicts: " + mergeResult.getConflicts());
        }
    }
}

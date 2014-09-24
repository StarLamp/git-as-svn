/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.IntList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNProperty;
import svnserver.StringHelper;
import svnserver.WikiConstants;
import svnserver.auth.User;
import svnserver.repository.*;
import svnserver.repository.git.cache.CacheChange;
import svnserver.repository.git.cache.CacheRevision;
import svnserver.repository.git.prop.GitProperty;
import svnserver.repository.git.prop.GitPropertyFactory;
import svnserver.repository.git.prop.PropertyMapping;
import svnserver.repository.locks.LockManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;

/**
 * Implementation for Git repository.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class GitRepository implements VcsRepository {
  private static final int REPORT_DELAY = 2500;
  private static final int MARK_NO_FILE = -1;

  @NotNull
  private static final Logger log = LoggerFactory.getLogger(GitRepository.class);
  @NotNull
  private final LockManager lockManager;
  @NotNull
  public static final byte[] emptyBytes = new byte[0];
  @NotNull
  private final Repository repository;
  @NotNull
  private final List<Repository> linkedRepositories;
  @NotNull
  private final GitPushMode pushMode;
  @NotNull
  private final List<GitRevision> revisions = new ArrayList<>();
  @NotNull
  private final TreeMap<Long, GitRevision> revisionByDate = new TreeMap<>();
  @NotNull
  private final TreeMap<ObjectId, GitRevision> revisionByHash = new TreeMap<>();
  @NotNull
  private final Map<String, IntList> lastUpdates = new ConcurrentHashMap<>();
  @NotNull
  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  // Lock for prevent concurrent pushes.
  @NotNull
  private final Object pushLock = new Object();
  @NotNull
  private final String uuid;
  @NotNull
  private final String gitBranch;
  @NotNull
  private final String svnBranch;
  @NotNull
  private final Map<String, String> md5Cache = new ConcurrentHashMap<>();
  @NotNull
  private final Map<ObjectId, GitProperty[]> directoryPropertyCache = new ConcurrentHashMap<>();
  @NotNull
  private final Map<ObjectId, GitProperty> filePropertyCache = new ConcurrentHashMap<>();
  private final boolean renameDetection;

  public GitRepository(@NotNull Repository repository,
                       @NotNull List<Repository> linked,
                       @NotNull GitPushMode pushMode,
                       @NotNull String branch,
                       boolean renameDetection,
                       @NotNull LockManager lockManager) throws IOException, SVNException {
    this.lockManager = lockManager;
    this.repository = repository;
    this.pushMode = pushMode;
    this.renameDetection = renameDetection;
    linkedRepositories = new ArrayList<>(linked);

    this.svnBranch = LayoutHelper.initRepository(repository).getName();
    this.gitBranch = Constants.R_HEADS + branch;
    loadRevisions();
    cacheRevisions();

    updateRevisions();
    this.uuid = UUID.nameUUIDFromBytes((getRepositoryId() + "\0" + gitBranch).getBytes(StandardCharsets.UTF_8)).toString();
    log.info("Repository ready (branch: {})", gitBranch);
  }

  @NotNull
  @Override
  public LockManager getLockManager() {
    return lockManager;
  }

  @NotNull
  private String getRepositoryId() throws IOException {
    return LayoutHelper.loadRepositoryId(repository.newObjectReader(), revisions.get(0).getCacheCommit());
  }

  /**
   * Load all cached revisions.
   *
   * @throws IOException
   * @throws SVNException
   */
  public boolean loadRevisions() throws IOException, SVNException {
    // Fast check.
    lock.readLock().lock();
    try {
      final int lastRevision = revisions.size() - 1;
      final ObjectId lastCommitId;
      if (lastRevision >= 0) {
        lastCommitId = revisions.get(lastRevision).getGitCommit();
        final Ref head = repository.getRef(svnBranch);
        if (head.getObjectId().equals(lastCommitId)) {
          return false;
        }
      }
    } finally {
      lock.readLock().unlock();
    }
    // Real loading.
    lock.writeLock().lock();
    try {
      final int lastRevision = revisions.size() - 1;
      final ObjectId lastCommitId = lastRevision < 0 ? null : revisions.get(lastRevision).getCacheCommit();
      final Ref head = repository.getRef(svnBranch);
      final List<RevCommit> newRevs = new ArrayList<>();
      final RevWalk revWalk = new RevWalk(repository);
      ObjectId objectId = head.getObjectId();
      while (true) {
        if (objectId.equals(lastCommitId)) {
          break;
        }
        final RevCommit commit = revWalk.parseCommit(objectId);
        newRevs.add(commit);
        if (commit.getParentCount() == 0) break;
        objectId = commit.getParent(0);
      }
      if (newRevs.isEmpty()) {
        return false;
      }
      final long beginTime = System.currentTimeMillis();
      int processed = 0;
      long reportTime = beginTime;
      log.info("Loading cached revision changes: {} revision", newRevs.size());
      for (int i = newRevs.size() - 1; i >= 0; i--) {
        loadRevisionInfo(newRevs.get(i));
        processed++;
        long currentTime = System.currentTimeMillis();
        if (currentTime - reportTime > REPORT_DELAY) {
          log.info("  processed cached revision: {} ({} rev/sec)", newRevs.size() - i, 1000.0f * processed / (currentTime - reportTime));
          reportTime = currentTime;
          processed = 0;
        }
      }
      final long endTime = System.currentTimeMillis();
      log.info("Cached revision loaded: {} ms", endTime - beginTime);
      return true;
    } finally {
      lock.writeLock().unlock();
    }
  }

  private static class CacheInfo {
    private final int id;
    @NotNull
    private RevCommit commit;
    @NotNull
    private List<CacheInfo> childs = new ArrayList<>();
    @NotNull
    private List<CacheInfo> parents = new ArrayList<>();
    @Nullable
    private String svnBranch;

    private CacheInfo(int id, @NotNull RevCommit commit) {
      this.id = id;
      this.commit = commit;
    }
  }

  /**
   * Create cache for new revisions.
   *
   * @throws IOException
   * @throws SVNException
   */
  public boolean cacheRevisions() throws IOException, SVNException {
    // Fast check.
    lock.readLock().lock();
    try {
      final int lastRevision = revisions.size() - 1;
      if (lastRevision >= 0) {
        final ObjectId lastCommitId = revisions.get(lastRevision).getGitCommit();
        final Ref master = repository.getRef(gitBranch);
        if ((master == null) || (master.getObjectId().equals(lastCommitId))) {
          return false;
        }
      }
    } finally {
      lock.readLock().unlock();
    }
    // Real update.
    final ObjectInserter inserter = repository.newObjectInserter();
    lock.writeLock().lock();
    try {
      final Ref master = repository.getRef(gitBranch);
      final List<RevCommit> newRevs = new ArrayList<>();
      final RevWalk revWalk = new RevWalk(repository);
      ObjectId objectId = master.getObjectId();
      while (true) {
        if (revisionByHash.containsKey(objectId)) {
          break;
        }
        final RevCommit commit = revWalk.parseCommit(objectId);
        newRevs.add(commit);
        if (commit.getParentCount() == 0) break;
        objectId = commit.getParent(0);
      }
      if (!newRevs.isEmpty()) {
        final long beginTime = System.currentTimeMillis();
        int processed = 0;
        long reportTime = beginTime;
        log.info("Loading revision changes: {} revision", newRevs.size());
        int revisionId = revisions.size();
        ObjectId cacheId = revisions.get(revisions.size() - 1).getCacheCommit();
        for (int i = newRevs.size() - 1; i >= 0; i--) {
          final RevCommit revCommit = newRevs.get(i);
          final CacheRevision cacheRevision = createCache(revCommit.getParentCount() > 0 ? revWalk.parseCommit(revCommit.getParent(0)) : null, revCommit, Collections.emptyMap(), revisionId);
          cacheId = LayoutHelper.createCacheCommit(inserter, cacheId, revCommit, cacheRevision);
          inserter.flush();

          processed++;
          long currentTime = System.currentTimeMillis();
          if (currentTime - reportTime > REPORT_DELAY) {
            log.info("  processed revision: {} ({} rev/sec)", newRevs.size() - i, 1000.0f * processed / (currentTime - reportTime));
            reportTime = currentTime;
            processed = 0;

            final RefUpdate refUpdate = repository.updateRef(svnBranch);
            refUpdate.setNewObjectId(cacheId);
            refUpdate.update();
          }
          revisionId++;
        }
        final long endTime = System.currentTimeMillis();
        log.info("Revision changes loaded: {} ms", endTime - beginTime);

        final RefUpdate refUpdate = repository.updateRef(svnBranch);
        refUpdate.setNewObjectId(cacheId);
        refUpdate.update();
      }
      return !newRevs.isEmpty();
    } finally {
      lock.writeLock().unlock();
    }
  }

  private CacheRevision createCache(@Nullable RevCommit oldCommit, @NotNull RevCommit newCommit, @NotNull Map<String, RevCommit> branches, int revisionId) throws IOException, SVNException {
    final GitFile oldTree = oldCommit == null ? new GitFile(this, null, "", GitProperty.emptyArray, revisionId - 1) : new GitFile(this, oldCommit, revisionId - 1);
    final GitFile newTree = new GitFile(this, newCommit, revisionId);
    final Map<String, CacheChange> fileChange = new TreeMap<>();
    for (Map.Entry<String, GitLogPair> entry : ChangeHelper.collectChanges(oldTree, newTree, true).entrySet()) {
      fileChange.put(entry.getKey(), new CacheChange(entry.getValue()));
    }
    return new CacheRevision(
        revisionId,
        newCommit,
        collectRename(oldTree, newTree),
        fileChange,
        branches
    );
  }

  @Override
  public void updateRevisions() throws IOException, SVNException {
    while (true) {
      loadRevisions();
      if (!cacheRevisions()) {
        break;
      }
    }
  }

  private void loadRevisionInfo(@NotNull RevCommit commit) throws IOException, SVNException {
    final RevWalk revWalk = new RevWalk(repository.newObjectReader());
    final CacheRevision cacheRevision = LayoutHelper.loadCacheRevision(revWalk.getObjectReader(), commit);
    final int revisionId = cacheRevision.getRevisionId();
    final Map<String, VcsCopyFrom> copyFroms = new HashMap<>();
    for (Map.Entry<String, String> entry : cacheRevision.getRenames().entrySet()) {
      copyFroms.put(entry.getKey(), new VcsCopyFrom(revisionId - 1, entry.getValue()));
    }
    final GitRevision oldCommit = revisions.isEmpty() ? null : revisions.get(revisions.size() - 1);
    final RevCommit svnCommit = cacheRevision.getGitCommitId() != null ? revWalk.parseCommit(cacheRevision.getGitCommitId()) : null;
    for (Map.Entry<String, CacheChange> entry : cacheRevision.getFileChange().entrySet()) {
      lastUpdates.compute(entry.getKey(), (key, list) -> {
        final IntList result = list == null ? new IntList() : list;
        result.add(revisionId);
        if (entry.getValue().getNewFile() == null) {
          result.add(MARK_NO_FILE);
        }
        return result;
      });
    }
    final GitRevision revision = new GitRevision(this, commit, cacheRevision.getRevisionId(), copyFroms, oldCommit, svnCommit);
    if (cacheRevision.getRevisionId() > 0) {
      if (revisionByDate.isEmpty() || revisionByDate.lastKey() <= revision.getDate()) {
        revisionByDate.put(revision.getDate(), revision);
      }
    }
    if (svnCommit != null) {
      revisionByHash.put(svnCommit.getId(), revision);
    }
    revisions.add(revision);
  }

  @NotNull
  private Map<String, String> collectRename(@NotNull GitFile oldTree, @NotNull GitFile newTree) throws IOException {
    if (!renameDetection) {
      return Collections.emptyMap();
    }
    final GitObject<ObjectId> oldTreeId = oldTree.getObjectId();
    final GitObject<ObjectId> newTreeId = newTree.getObjectId();
    if (oldTreeId == null || newTreeId == null || !Objects.equals(oldTreeId.getRepo(), newTreeId.getRepo())) {
      return Collections.emptyMap();
    }
    final TreeWalk tw = new TreeWalk(repository);
    tw.setRecursive(true);
    tw.addTree(oldTree.getObjectId().getObject());
    tw.addTree(newTree.getObjectId().getObject());

    final RenameDetector rd = new RenameDetector(repository);
    rd.addAll(DiffEntry.scan(tw));

    final Map<String, String> result = new HashMap<>();
    for (DiffEntry diff : rd.compute(tw.getObjectReader(), null)) {
      if (diff.getScore() >= rd.getRenameScore()) {
        result.put(StringHelper.normalize(diff.getNewPath()), StringHelper.normalize(diff.getOldPath()));
      }
    }
    return result;
  }

  @NotNull
  public GitProperty[] collectProperties(@NotNull GitTreeEntry treeEntry, @NotNull VcsSupplier<Iterable<GitTreeEntry>> entryProvider) throws IOException, SVNException {
    if (treeEntry.getFileMode().getObjectType() == Constants.OBJ_BLOB)
      return GitProperty.emptyArray;

    GitProperty[] props = directoryPropertyCache.get(treeEntry.getObjectId().getObject());
    if (props == null) {
      final List<GitProperty> propList = new ArrayList<>();
      for (GitTreeEntry entry : entryProvider.get()) {
        final GitProperty property = parseGitProperty(entry.getFileName(), entry.getObjectId());
        if (property != null) {
          propList.add(property);
        }
      }
      if (!propList.isEmpty()) {
        props = propList.toArray(new GitProperty[propList.size()]);
      } else {
        props = GitProperty.emptyArray;
      }
      directoryPropertyCache.put(treeEntry.getObjectId().getObject(), props);
    }
    return props;
  }

  @Nullable
  private GitProperty parseGitProperty(@NotNull String fileName, @NotNull GitObject<ObjectId> objectId) throws IOException, SVNException {
    final GitPropertyFactory factory = PropertyMapping.getFactory(fileName);
    if (factory == null)
      return null;

    return cachedParseGitProperty(objectId, factory);
  }

  @Nullable
  private GitProperty cachedParseGitProperty(GitObject<ObjectId> objectId, GitPropertyFactory factory) throws IOException, SVNException {
    GitProperty property = filePropertyCache.get(objectId.getObject());
    if (property == null) {
      property = factory.create(loadContent(objectId));
      filePropertyCache.put(objectId.getObject(), property);
    }
    return property;
  }

  @NotNull
  @Override
  public GitRevision getLatestRevision() throws IOException {
    lock.readLock().lock();
    try {
      return revisions.get(revisions.size() - 1);
    } finally {
      lock.readLock().unlock();
    }
  }

  @NotNull
  @Override
  public VcsRevision getRevisionByDate(long dateTime) throws IOException {
    lock.readLock().lock();
    try {
      final Map.Entry<Long, GitRevision> entry = revisionByDate.floorEntry(dateTime);
      if (entry != null) {
        return entry.getValue();
      }
      return revisions.get(0);
    } finally {
      lock.readLock().unlock();
    }
  }

  @NotNull
  @Override
  public String getUuid() {
    return uuid;
  }

  @NotNull
  public Repository getRepository() {
    return repository;
  }

  @NotNull
  public String getObjectMD5(@NotNull GitObject<? extends ObjectId> objectId, char type, @NotNull VcsSupplier<InputStream> streamFactory) throws IOException, SVNException {
    final String key = type + objectId.getObject().name();
    String result = md5Cache.get(key);
    if (result == null) {
      final byte[] buffer = new byte[64 * 1024];
      final MessageDigest md5 = getMd5();
      try (InputStream stream = streamFactory.get()) {
        while (true) {
          int size = stream.read(buffer);
          if (size < 0) break;
          md5.update(buffer, 0, size);
        }
      }
      result = StringHelper.toHex(md5.digest());
      md5Cache.putIfAbsent(key, result);
    }
    return result;
  }

  @NotNull
  @Override
  public GitRevision getRevisionInfo(int revision) throws IOException, SVNException {
    final GitRevision revisionInfo = getRevisionInfoUnsafe(revision);
    if (revisionInfo == null) {
      throw new SVNException(SVNErrorMessage.create(SVNErrorCode.FS_NO_SUCH_REVISION, "No such revision " + revision));
    }
    return revisionInfo;
  }

  @NotNull
  public GitRevision sureRevisionInfo(int revision) throws IOException {
    final GitRevision revisionInfo = getRevisionInfoUnsafe(revision);
    if (revisionInfo == null) {
      throw new IllegalStateException("No such revision " + revision);
    }
    return revisionInfo;
  }

  @Nullable
  private GitRevision getRevisionInfoUnsafe(int revision) throws IOException {
    lock.readLock().lock();
    try {
      if (revision >= revisions.size())
        return null;
      return revisions.get(revision);
    } finally {
      lock.readLock().unlock();
    }
  }

  private static MessageDigest getMd5() {
    try {
      return MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @NotNull
  private GitRevision getRevision(@NotNull ObjectId revisionId) throws SVNException {
    lock.readLock().lock();
    try {
      final GitRevision revision = revisionByHash.get(revisionId);
      if (revision == null) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.FS_NO_SUCH_REVISION, "No such revision " + revisionId.name()));
      }
      return revision;
    } finally {
      lock.readLock().unlock();
    }
  }

  @NotNull
  @Override
  public VcsDeltaConsumer createFile() throws IOException, SVNException {
    return new GitDeltaConsumer(this, null);
  }

  @NotNull
  @Override
  public VcsDeltaConsumer modifyFile(@NotNull VcsFile file) throws IOException, SVNException {
    return new GitDeltaConsumer(this, (GitFile) file);
  }

  @Override
  public int getLastChange(@NotNull String nodePath, int beforeRevision) {
    if (nodePath.isEmpty()) return beforeRevision;
    final IntList revs = this.lastUpdates.get(nodePath);
    if (revs != null) {
      int prev = 0;
      for (int i = revs.size() - 1; i >= 0; --i) {
        final int rev = revs.get(i);
        if ((rev >= 0) && (rev <= beforeRevision)) {
          if (prev == MARK_NO_FILE) {
            return MARK_NO_FILE;
          }
          return rev;
        }
        prev = rev;
      }
    }
    return MARK_NO_FILE;
  }

  @NotNull
  @Override
  public VcsCommitBuilder createCommitBuilder() throws IOException, SVNException {
    return new GitCommitBuilder(gitBranch);
  }

  @NotNull
  public static String loadContent(@NotNull GitObject<? extends ObjectId> objectId) throws IOException {
    final byte[] bytes = objectId.getRepo().newObjectReader().open(objectId.getObject()).getBytes();
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @NotNull
  public Iterable<GitTreeEntry> loadTree(@Nullable GitTreeEntry tree) throws IOException {
    final GitObject<ObjectId> treeId = getTreeObject(tree);
    // Loading tree.
    if (treeId == null) {
      return Collections.emptyList();
    }
    final List<GitTreeEntry> result = new ArrayList<>();
    final Repository repo = treeId.getRepo();
    final CanonicalTreeParser treeParser = new CanonicalTreeParser(GitRepository.emptyBytes, repo.newObjectReader(), treeId.getObject());
    while (!treeParser.eof()) {
      result.add(new GitTreeEntry(
          treeParser.getEntryFileMode(),
          new GitObject<>(repo, treeParser.getEntryObjectId()),
          treeParser.getEntryPathString()
      ));
      treeParser.next();
    }
    return result;
  }

  @Nullable
  private GitObject<ObjectId> getTreeObject(@Nullable GitTreeEntry tree) throws IOException {
    if (tree == null) {
      return null;
    }
    // Get tree object
    if (tree.getFileMode().equals(FileMode.TREE)) {
      return tree.getObjectId();
    }
    if (tree.getFileMode().equals(FileMode.GITLINK)) {
      GitObject<RevCommit> linkedCommit = loadLinkedCommit(tree.getObjectId().getObject());
      if (linkedCommit == null) {
        return null;
      }
      return new GitObject<>(linkedCommit.getRepo(), linkedCommit.getObject().getTree());
    } else {
      return null;
    }
  }

  @Nullable
  public GitObject<RevCommit> loadLinkedCommit(@NotNull ObjectId objectId) throws IOException {
    for (Repository repo : linkedRepositories) {
      if (repo.hasObject(objectId)) {
        final RevWalk revWalk = new RevWalk(repo);
        return new GitObject<>(repo, revWalk.parseCommit(objectId));

      }
    }
    return null;
  }

  private static class ComputeBranchName implements BiFunction<RevCommit, CacheInfo, CacheInfo> {
    @NotNull
    private final String svnBranch;

    public ComputeBranchName(@NotNull String svnBranch) {
      this.svnBranch = svnBranch;
    }

    @NotNull
    @Override
    public CacheInfo apply(@NotNull RevCommit revCommit, @NotNull CacheInfo old) {
      if (old.svnBranch == null || LayoutHelper.compareBranches(old.svnBranch, svnBranch) > 0) {
        old.svnBranch = svnBranch;
      }
      return old;
    }
  }

  private class GitPropertyValidator {
    @NotNull
    private final Deque<GitFile> treeStack;

    public GitPropertyValidator(@NotNull GitFile root) {
      this.treeStack = new ArrayDeque<>();
      this.treeStack.push(root);
    }

    public void openDir(@NotNull String name) throws IOException, SVNException {
      final GitFile file = treeStack.element().getEntry(name);
      if (file == null) {
        throw new IllegalStateException("Invalid state: can't find file " + name + " in created commit.");
      }
      treeStack.push(file);
    }

    public void checkProperties(@Nullable String name, @NotNull Map<String, String> properties) throws IOException, SVNException {
      final GitFile dir = treeStack.element();
      final GitFile node = name == null ? dir : dir.getEntry(name);
      if (node == null) {
        throw new IllegalStateException("Invalid state: can't find entry " + name + " in created commit.");
      }
      final Map<String, String> expected = node.getProperties(false);
      if (!properties.equals(expected)) {
        final StringBuilder message = new StringBuilder();
        message.append("Can't commit entry: ").append(node.getFullPath()).append("\nInvalid svn properties found.\n");
        message.append("Expected:\n");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
          message.append("  ").append(entry.getKey()).append(" = \"").append(entry.getValue()).append("\"\n");
        }
        message.append("Actual:\n");
        for (Map.Entry<String, String> entry : properties.entrySet()) {
          message.append("  ").append(entry.getKey()).append(" = \"").append(entry.getValue()).append("\"\n");
        }

        message.append("\n"
            + "----------------\n" +
            "Subversion properties must be consistent with Git config files:\n");
        for (String configFile : PropertyMapping.getRegisteredFiles()) {
          message.append("  ").append(configFile).append('\n');
        }
        message.append("\n" +
            "For more detailed information you can see: ").append(WikiConstants.PROPERTIES).append("\n");
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.REPOS_HOOK_FAILURE, message.toString()));
      }
    }

    public void closeDir() {
      treeStack.pop();
    }
  }

  private class GitCommitBuilder implements VcsCommitBuilder {
    @NotNull
    private final Deque<GitTreeUpdate> treeStack;
    @NotNull
    private final ObjectInserter inserter;
    @NotNull
    private final GitRevision revision;
    @NotNull
    private final String branch;
    @NotNull
    private final List<VcsConsumer<GitPropertyValidator>> validateActions = new ArrayList<>();

    public GitCommitBuilder(@NotNull String branch) throws IOException, SVNException {
      this.inserter = repository.newObjectInserter();
      this.branch = branch;
      this.revision = getLatestRevision();
      this.treeStack = new ArrayDeque<>();
      this.treeStack.push(new GitTreeUpdate("", getOriginalTree()));
    }

    private Iterable<GitTreeEntry> getOriginalTree() throws IOException {
      final RevCommit commit = revision.getGitCommit();
      if (commit == null) {
        return Collections.emptyList();
      }
      return loadTree(new GitTreeEntry(repository, FileMode.TREE, commit.getTree(), ""));
    }

    @Override
    public void checkUpToDate(@NotNull String path, int rev) throws SVNException, IOException {
      final GitFile file = revision.getFile(path);
      if (file == null) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, path));
      } else if (file.getLastChange().getId() > rev) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.WC_NOT_UP_TO_DATE, "Working copy is not up-to-date: " + path));
      }
    }

    @Override
    public void addDir(@NotNull String name, @Nullable VcsFile sourceDir) throws SVNException, IOException {
      final GitTreeUpdate current = treeStack.element();
      if (current.getEntries().containsKey(name)) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.FS_ALREADY_EXISTS, getFullPath(name)));
      }
      final GitFile source = (GitFile) sourceDir;
      validateActions.add(validator -> validator.openDir(name));
      treeStack.push(new GitTreeUpdate(name, loadTree(source == null ? null : source.getTreeEntry())));
    }

    @Override
    public void openDir(@NotNull String name) throws SVNException, IOException {
      final GitTreeUpdate current = treeStack.element();
      // todo: ???
      final GitTreeEntry originalDir = current.getEntries().remove(name);
      if ((originalDir == null) || (!originalDir.getFileMode().equals(FileMode.TREE))) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, getFullPath(name)));
      }
      validateActions.add(validator -> validator.openDir(name));
      treeStack.push(new GitTreeUpdate(name, loadTree(originalDir)));
    }

    @Override
    public void checkDirProperties(@NotNull Map<String, String> props) throws SVNException, IOException {
      validateActions.add(validator -> validator.checkProperties(null, props));
    }

    @Override
    public void closeDir() throws SVNException, IOException {
      final GitTreeUpdate last = treeStack.pop();
      final GitTreeUpdate current = treeStack.element();
      final String fullPath = getFullPath(last.getName());
      if (last.getEntries().isEmpty()) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.CANCELLED, "Empty directories is not supported: " + fullPath));
      }
      final ObjectId subtreeId = last.buildTree(inserter);
      log.info("Create tree {} for dir: {}", subtreeId.name(), fullPath);
      if (current.getEntries().put(last.getName(), new GitTreeEntry(FileMode.TREE, new GitObject<>(repository, subtreeId), last.getName())) != null) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.FS_ALREADY_EXISTS, fullPath));
      }
      validateActions.add(GitPropertyValidator::closeDir);
    }

    @Override
    public void saveFile(@NotNull String name, @NotNull VcsDeltaConsumer deltaConsumer, boolean modify) throws SVNException, IOException {
      final GitDeltaConsumer gitDeltaConsumer = (GitDeltaConsumer) deltaConsumer;
      final GitTreeUpdate current = treeStack.element();
      final GitTreeEntry entry = current.getEntries().get(name);
      final GitObject<ObjectId> originalId = gitDeltaConsumer.getOriginalId();
      if (modify ^ (entry != null)) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.WC_NOT_UP_TO_DATE, "Working copy is not up-to-date: " + getFullPath(name)));
      }
      final GitObject<ObjectId> objectId = gitDeltaConsumer.getObjectId();
      if (objectId == null) {
        // Content not updated.
        if (originalId == null) {
          throw new SVNException(SVNErrorMessage.create(SVNErrorCode.INCOMPLETE_DATA, "Added file without content: " + getFullPath(name)));
        }
        return;
      }
      current.getEntries().put(name, new GitTreeEntry(getFileMode(gitDeltaConsumer.getProperties()), objectId, name));
      validateActions.add(validator -> validator.checkProperties(name, gitDeltaConsumer.getProperties()));
    }

    private FileMode getFileMode(@NotNull Map<String, String> props) {
      if (props.containsKey(SVNProperty.SPECIAL)) return FileMode.SYMLINK;
      if (props.containsKey(SVNProperty.EXECUTABLE)) return FileMode.EXECUTABLE_FILE;
      return FileMode.REGULAR_FILE;
    }

    @Override
    public void delete(@NotNull String name) throws SVNException, IOException {
      final GitTreeUpdate current = treeStack.element();
      final GitTreeEntry entry = current.getEntries().remove(name);
      if (entry == null) {
        throw new SVNException(SVNErrorMessage.create(SVNErrorCode.ENTRY_NOT_FOUND, getFullPath(name)));
      }
    }

    @Override
    public GitRevision commit(@NotNull User userInfo, @NotNull String message) throws SVNException, IOException {
      synchronized (pushLock) {
        final GitTreeUpdate root = treeStack.element();
        final ObjectId treeId = root.buildTree(inserter);
        log.info("Create tree {} for commit.", treeId.name());
        inserter.flush();

        final CommitBuilder commitBuilder = new CommitBuilder();
        final PersonIdent ident = createIdent(userInfo);
        commitBuilder.setAuthor(ident);
        commitBuilder.setCommitter(ident);
        commitBuilder.setMessage(message);
        final RevCommit parentCommit = revision.getGitCommit();
        if (parentCommit != null) {
          commitBuilder.setParentId(parentCommit.getId());
        }
        commitBuilder.setTreeId(treeId);
        final ObjectId commitId = inserter.insert(commitBuilder);
        inserter.flush();

        log.info("Validate properties");
        validateProperties(new RevWalk(repository).parseCommit(commitId));

        log.info("Create commit {}: {}", commitId.name(), message);
        log.info("Try to push commit in branch: {}", branch);
        if (!pushMode.push(repository, commitId, branch)) {
          log.info("Non fast forward push rejected");
          return null;
        }
        log.info("Commit is pushed");

        updateRevisions();
        return getRevision(commitId);
      }
    }

    private void validateProperties(@NotNull RevCommit commit) throws IOException, SVNException {
      final GitFile root = new GitFile(GitRepository.this, commit, 0);
      final GitPropertyValidator validator = new GitPropertyValidator(root);
      for (VcsConsumer<GitPropertyValidator> validateAction : validateActions) {
        validateAction.accept(validator);
      }
    }

    private PersonIdent createIdent(User userInfo) {
      final String realName = userInfo.getRealName();
      final String email = userInfo.getEmail();
      return new PersonIdent(realName, email == null ? "" : email);
    }

    @NotNull
    private String getFullPath(String name) {
      final StringBuilder fullPath = new StringBuilder();
      final Iterator<GitTreeUpdate> iter = treeStack.descendingIterator();
      while (iter.hasNext()) {
        fullPath.append(iter.next().getName()).append('/');
      }
      fullPath.append(name);
      return fullPath.toString();
    }
  }
}

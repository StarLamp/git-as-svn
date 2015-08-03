/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.server;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLock;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.ISVNLockHandler;
import org.tmatesoft.svn.core.io.SVNRepository;
import svnserver.StringHelper;
import svnserver.tester.SvnTester;
import svnserver.tester.SvnTesterDataProvider;
import svnserver.tester.SvnTesterExternalListener;
import svnserver.tester.SvnTesterFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static svnserver.SvnTestHelper.*;

/**
 * Check svn locking.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
@Listeners(SvnTesterExternalListener.class)
public class SvnLockTest {
  private static final int MAX_RELOCK_COUNT = 100;

  /**
   * Check to take lock on absent file.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void lockNotExists(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();
      createFile(repo, "/example.txt", "", null);
      lock(repo, "example2.txt", repo.getLatestRevision(), false, SVNErrorCode.FS_OUT_OF_DATE);
    }
  }

  /**
   * Check to take lock of out-of-date file.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void lockOutOfDate(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      modifyFile(repo, "/example.txt", "content", latestRevision);
      lock(repo, "example.txt", latestRevision, false, SVNErrorCode.FS_OUT_OF_DATE);
    }
  }

  /**
   * Check to take lock of out-of-date file.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void lockNotFile(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      final ISVNEditor editor = repo.getCommitEditor("Initial state", null, false, null);
      editor.openRoot(-1);
      editor.addDir("/example", null, -1);
      editor.addFile("/example/example.txt", null, -1);
      sendDeltaAndClose(editor, "/example/example.txt", null, "Source content");
      editor.closeDir();
      editor.closeDir();
      editor.closeEdit();

      final long latestRevision = repo.getLatestRevision();
      lock(repo, "example", latestRevision, false, SVNErrorCode.FS_NOT_FILE);
    }
  }

  /**
   * Check to stealing lock.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void lockForce(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      SVNLock oldLock = lock(repo, "example.txt", latestRevision, false, null);
      Assert.assertNotNull(oldLock);
      compareLock(oldLock, repo.getLock("example.txt"));

      SVNLock badLock = lock(repo, "example.txt", latestRevision, false, SVNErrorCode.FS_PATH_ALREADY_LOCKED);
      Assert.assertNull(badLock);
      compareLock(oldLock, repo.getLock("example.txt"));

      SVNLock newLock = lock(repo, "example.txt", latestRevision, true, null);
      Assert.assertNotNull(newLock);
      compareLock(newLock, repo.getLock("example.txt"));
    }
  }

  /**
   * Check to break lock.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void unlockForce(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      SVNLock oldLock = lock(repo, "example.txt", latestRevision, false, null);
      Assert.assertNotNull(oldLock);
      unlock(repo, oldLock, false, null);

      SVNLock newLock = lock(repo, "example.txt", latestRevision, true, null);
      Assert.assertNotNull(newLock);
      compareLock(newLock, repo.getLock("example.txt"));

      unlock(repo, oldLock, true, null);
      Assert.assertNull(repo.getLock("example.txt"));
    }
  }

  /**
   * Check to take lock of out-of-date file.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void lockSimple(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      createFile(repo, "/example2.txt", "", null);

      Assert.assertNull(repo.getLock("example.txt"));

      // New lock
      final SVNLock lock = lock(repo, "example.txt", latestRevision, false, null);
      Assert.assertNotNull(lock);
      compareLock(repo.getLock("example.txt"), lock);

      // Already locked
      lock(repo, "example.txt", latestRevision, false, SVNErrorCode.FS_PATH_ALREADY_LOCKED);

      // Lock must not changed
      compareLock(repo.getLock("example.txt"), lock);

      unlock(repo, lock, false, null);

      Assert.assertNull(repo.getLock("example.txt"));

      // Lock again
      lock(repo, "example.txt", latestRevision, false, null);
    }
  }

  /**
   * Check for deny modify locking file.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void modifyLocked(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      // Lock
      final SVNLock lock = lock(repo, "example.txt", latestRevision, false, null);
      Assert.assertNotNull(lock);

      try {
        modifyFile(repo, "/example.txt", "content", latestRevision);
        Assert.fail();
      } catch (SVNException e) {
        Assert.assertEquals(e.getErrorMessage().getErrorCode(), SVNErrorCode.FS_BAD_LOCK_TOKEN);
      }
    }
  }

  /**
   * Check for deny modify locking file.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void modifyLockedInvalidLock(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      // Lock
      final SVNLock oldLock = lock(repo, "example.txt", latestRevision, false, null);
      Assert.assertNotNull(oldLock);
      unlock(repo, oldLock, false, null);

      final SVNLock newLock = lock(repo, "example.txt", latestRevision, false, null);
      try {
        final Map<String, String> locks = new HashMap<>();
        locks.put(oldLock.getPath(), oldLock.getID());
        final ISVNEditor editor = repo.getCommitEditor("Initial state", locks, false, null);
        editor.openRoot(-1);
        editor.openFile("/example.txt", latestRevision);
        sendDeltaAndClose(editor, "/example.txt", "", "Source content");
        editor.closeDir();
        editor.closeEdit();
        Assert.fail();
      } catch (SVNException e) {
        Assert.assertEquals(e.getErrorMessage().getErrorCode(), SVNErrorCode.FS_BAD_LOCK_TOKEN);
      }
      compareLock(server.openSvnRepository().getLock("/example.txt"), newLock);
    }
  }

  /**
   * Check for commit with keep locks.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void modifyLockedRemoveLock(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      // Lock
      final SVNLock lock = lock(repo, "example.txt", latestRevision, false, null);
      Assert.assertNotNull(lock);
      {
        final Map<String, String> locks = new HashMap<>();
        locks.put("/example.txt", lock.getID());
        final ISVNEditor editor = repo.getCommitEditor("Initial state", locks, false, null);
        editor.openRoot(-1);
        editor.openFile("/example.txt", latestRevision);
        sendDeltaAndClose(editor, "/example.txt", "", "Source content");
        editor.closeDir();
        editor.closeEdit();
      }
      Assert.assertNull(repo.getLock("/example.txt"));
    }
  }

  /**
   * Check for commit with remove locks.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void modifyLockedKeepLock(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      // Lock
      final SVNLock lock = lock(repo, "example.txt", latestRevision, false, null);
      Assert.assertNotNull(lock);
      {
        final Map<String, String> locks = new HashMap<>();
        locks.put("/example.txt", lock.getID());
        final ISVNEditor editor = repo.getCommitEditor("Initial state", locks, true, null);
        editor.openRoot(-1);
        editor.openFile("/example.txt", latestRevision);
        sendDeltaAndClose(editor, "/example.txt", "", "Source content");
        editor.closeDir();
        editor.closeEdit();
      }
      compareLock(repo.getLock("/example.txt"), lock);
    }
  }

  /**
   * Check for deny modify locking file.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void deleteLocked(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      // Lock
      final SVNLock lock = lock(repo, "example.txt", latestRevision, false, null);
      Assert.assertNotNull(lock);

      try {
        final ISVNEditor editor = repo.getCommitEditor("Initial state", null, false, null);
        editor.openRoot(-1);
        editor.deleteEntry("/example.txt", latestRevision);
        editor.closeDir();
        editor.closeEdit();
        Assert.fail();
      } catch (SVNException e) {
        Assert.assertEquals(e.getErrorMessage().getErrorCode(), SVNErrorCode.FS_BAD_LOCK_TOKEN);
      }
    }
  }

  /**
   * Check for deny modify locking file.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void deleteLockedDirNoLock(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();
      {
        final ISVNEditor editor = repo.getCommitEditor("Initial state", null, false, null);
        editor.openRoot(-1);
        editor.addDir("/example", null, -1);
        editor.addFile("/example/example.txt", null, -1);
        sendDeltaAndClose(editor, "/example/example.txt", null, "Source content");
        editor.closeDir();
        editor.closeDir();
        editor.closeEdit();
      }
      final long latestRevision = repo.getLatestRevision();
      // Lock
      final SVNLock lock = lock(repo, "/example/example.txt", latestRevision, false, null);
      Assert.assertNotNull(lock);
      try {
        final ISVNEditor editor = repo.getCommitEditor("Initial state", null, false, null);
        editor.openRoot(-1);
        editor.deleteEntry("/example", latestRevision);
        editor.closeDir();
        editor.closeEdit();
        Assert.fail();
      } catch (SVNException e) {
        Assert.assertEquals(e.getErrorMessage().getErrorCode(), SVNErrorCode.FS_BAD_LOCK_TOKEN);
      }
    }
  }

  /**
   * Check get locks.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void getLocks(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();
      {
        final ISVNEditor editor = repo.getCommitEditor("Initial state", null, false, null);
        editor.openRoot(-1);
        editor.addDir("/example", null, -1);
        editor.addFile("/example/example.txt", null, -1);
        sendDeltaAndClose(editor, "/example/example.txt", null, "Source content");
        editor.closeDir();
        editor.addFile("/foo.txt", null, -1);
        sendDeltaAndClose(editor, "/foo.txt", null, "Source content");
        editor.closeDir();
        editor.closeEdit();
      }
      compareLocks(repo.getLocks(""));

      final long latestRevision = repo.getLatestRevision();
      // Lock
      final SVNLock lock1 = lock(repo, "/example/example.txt", latestRevision, false, null);
      Assert.assertNotNull(lock1);
      final SVNLock lock2 = lock(repo, "/foo.txt", latestRevision, false, null);
      Assert.assertNotNull(lock2);

      compareLocks(repo.getLocks(""), lock1, lock2);
    }
  }

  /**
   * Check for deny modify locking file.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void deleteLockedDirWithLock(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();
      {
        final ISVNEditor editor = repo.getCommitEditor("Initial state", null, false, null);
        editor.openRoot(-1);
        editor.addDir("/example", null, -1);
        editor.addFile("/example/example.txt", null, -1);
        sendDeltaAndClose(editor, "/example/example.txt", null, "Source content");
        editor.closeDir();
        editor.closeDir();
        editor.closeEdit();
      }
      final long latestRevision = repo.getLatestRevision();
      // Lock
      final SVNLock lock = lock(repo, "/example/example.txt", latestRevision, false, null);
      Assert.assertNotNull(lock);
      final Map<String, String> locks = new HashMap<>();
      locks.put(lock.getPath(), lock.getID());
      final ISVNEditor editor = repo.getCommitEditor("Initial state", locks, false, null);
      editor.openRoot(-1);
      editor.deleteEntry("/example", latestRevision);
      editor.closeDir();
      editor.closeEdit();
    }
  }

  /**
   * Try to twice remove lock.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void unlockTwice(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      // New lock
      final SVNLock lock = lock(repo, "example.txt", latestRevision, false, null);
      Assert.assertNotNull(lock);
      unlock(repo, lock, false, null);
      unlock(repo, lock, false, SVNErrorCode.FS_NO_SUCH_LOCK);
    }
  }

  /**
   * Try to remove not-owned lock.
   *
   * @throws Exception
   */
  @Test(dataProvider = "all", dataProviderClass = SvnTesterDataProvider.class)
  public void unlockNotOwner(@NotNull SvnTesterFactory factory) throws Exception {
    try (SvnTester server = factory.create()) {
      final SVNRepository repo = server.openSvnRepository();

      createFile(repo, "/example.txt", "", null);
      final long latestRevision = repo.getLatestRevision();

      // New lock
      final SVNLock oldLock = lock(repo, "example.txt", latestRevision, false, null);
      Assert.assertNotNull(oldLock);
      unlock(repo, oldLock, false, null);

      // todo #79: Workaround for SvnKit bug: SVNUUIDGenerator.generateUUID() generates non-unique value because bug in SVNUUIDGenerator.timestamp()
      for (int pass = 0; ; ++pass) {
        Assert.assertTrue(pass < MAX_RELOCK_COUNT);
        final SVNLock newLock = lock(repo, "example.txt", latestRevision, false, null);
        Assert.assertNotNull(newLock);
        if (!newLock.getID().equals(oldLock.getID())) {
          break;
        }
        unlock(repo, newLock, false, null);
      }

      unlock(repo, oldLock, false, SVNErrorCode.FS_NO_SUCH_LOCK);
    }
  }

  private void compareLock(@Nullable SVNLock actual, @Nullable SVNLock expeted) {
    if (expeted == null) {
      Assert.assertNull(actual);
    } else {
      Assert.assertNotNull(actual);
      Assert.assertEquals(actual.getID(), expeted.getID());
    }
  }

  private void compareLocks(SVNLock[] actual, SVNLock... expected) {
    Map<String, SVNLock> actualLocks = new HashMap<>();
    for (SVNLock lock : actual) {
      actualLocks.put(lock.getPath(), lock);
    }
    for (SVNLock lock : expected) {
      compareLock(actualLocks.remove(lock.getPath()), lock);
    }
    Assert.assertTrue(actualLocks.isEmpty());
  }

  @Nullable
  private SVNLock lock(@NotNull SVNRepository repo, @NotNull String path, long revision, boolean force, @Nullable SVNErrorCode errorCode) {
    final Map<String, Long> pathsToRevisions = new HashMap<>();
    pathsToRevisions.put(path, revision);
    final List<SVNLock> locks = new ArrayList<>();
    try {
      repo.lock(pathsToRevisions, null, force, new ISVNLockHandler() {
        @Override
        public void handleLock(@NotNull String path, @Nullable SVNLock lock, @Nullable SVNErrorMessage error) throws SVNException {
          if (error != null) {
            throw new SVNException(error);
          }
          Assert.assertNull(errorCode);
          Assert.assertNotNull(lock);
          locks.add(lock);
        }

        @Override
        public void handleUnlock(String path, SVNLock lock, SVNErrorMessage error) throws SVNException {
          Assert.fail();
        }
      });
      Assert.assertNull(errorCode);
      Assert.assertTrue(locks.size() <= 1);
      return locks.isEmpty() ? null : locks.get(0);
    } catch (SVNException e) {
      Assert.assertEquals(e.getErrorMessage().getErrorCode(), errorCode);
      return null;
    }
  }

  private void unlock(@NotNull SVNRepository repo, @NotNull SVNLock lock, boolean breakLock, @Nullable SVNErrorCode errorCode) {
    try {
      final Map<String, String> pathsToTokens = new HashMap<>();
      final String root = repo.getLocation().getPath().substring(repo.getRepositoryRoot(true).getPath().length());
      Assert.assertTrue(lock.getPath().startsWith(root));
      pathsToTokens.put(StringHelper.normalize(lock.getPath().substring(root.length())), lock.getID());
      repo.unlock(pathsToTokens, breakLock, new ISVNLockHandler() {
        @Override
        public void handleLock(@NotNull String path, @Nullable SVNLock lock, @Nullable SVNErrorMessage error) throws SVNException {
          Assert.fail();
        }

        @Override
        public void handleUnlock(String path, SVNLock removedLock, SVNErrorMessage error) throws SVNException {
          if (error != null) {
            throw new SVNException(error);
          }
          Assert.assertNull(errorCode);
          Assert.assertNotNull(removedLock);
          compareLock(removedLock, lock);
        }
      });
      Assert.assertNull(errorCode);
    } catch (SVNException e) {
      Assert.assertEquals(e.getErrorMessage().getErrorCode(), errorCode);
    }
  }
}

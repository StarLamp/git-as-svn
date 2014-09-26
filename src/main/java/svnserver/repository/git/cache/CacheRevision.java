/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git.cache;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Revision cache information.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class CacheRevision {
  @NotNull
  public static final CacheRevision empty = new CacheRevision();

  private final int revisionId;
  @Nullable
  private final ObjectId gitCommitId;
  @NotNull
  private final Map<String, String> renames = new TreeMap<>();
  @NotNull
  private final Map<String, CacheChange> fileChange = new TreeMap<>();
  @NotNull
  private final Map<String, RevCommit> branches = new TreeMap<>();

  protected CacheRevision() {
    this.revisionId = 0;
    this.gitCommitId = null;
  }

  public CacheRevision(
      int revisionId,
      @Nullable RevCommit svnCommit,
      @NotNull Map<String, String> renames,
      @NotNull Map<String, CacheChange> fileChange,
      @NotNull Map<String, RevCommit> branches
  ) {
    this.revisionId = revisionId;
    if (svnCommit != null) {
      this.gitCommitId = svnCommit.getId();
    } else {
      this.gitCommitId = null;
    }
    this.renames.putAll(renames);
    this.fileChange.putAll(fileChange);
    this.branches.putAll(branches);
  }

  public int getRevisionId() {
    return revisionId;
  }

  @Nullable
  public ObjectId getGitCommitId() {
    return gitCommitId;
  }

  @NotNull
  public Map<String, String> getRenames() {
    return Collections.unmodifiableMap(renames);
  }

  @NotNull
  public Map<String, CacheChange> getFileChange() {
    return Collections.unmodifiableMap(fileChange);
  }

  @NotNull
  public Map<String, RevCommit> getBranches() {
    return Collections.unmodifiableMap(branches);
  }
}

/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNRevisionProperty;
import svnserver.SvnConstants;
import svnserver.repository.VcsCopyFrom;
import svnserver.repository.VcsRevision;
import svnserver.repository.git.prop.GitProperty;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Git revision.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public class GitRevision implements VcsRevision {
  @NotNull
  private final GitRepository repo;
  @NotNull
  private final ObjectId cacheCommit;
  @Nullable
  private final GitRevision previous;
  @Nullable
  private final RevCommit gitNewCommit;

  @NotNull
  private final Map<String, VcsCopyFrom> renames;
  private final long date;
  private final int revision;

  public GitRevision(@NotNull GitRepository repo,
                     @NotNull ObjectId cacheCommit,
                     int revision,
                     @NotNull Map<String, VcsCopyFrom> renames,
                     @Nullable GitRevision previous,
                     @Nullable RevCommit gitNewCommit,
                     int commitTimeSec) {
    this.repo = repo;
    this.cacheCommit = cacheCommit;
    this.revision = revision;
    this.renames = renames;
    this.previous = previous;
    this.gitNewCommit = gitNewCommit;
    this.date = TimeUnit.SECONDS.toMillis(commitTimeSec);
  }

  @NotNull
  public ObjectId getCacheCommit() {
    return cacheCommit;
  }

  @Override
  public int getId() {
    return revision;
  }

  @Nullable
  public RevCommit getGitNewCommit() {
    return gitNewCommit;
  }

  @NotNull
  @Override
  public Map<String, GitLogEntry> getChanges() throws IOException, SVNException {
    if (gitNewCommit == null) {
      return Collections.emptyMap();
    }
    final GitFile oldTree = previous == null ? emptyFile() : previous.getRoot();
    final GitFile newTree = getRoot();

    final Map<String, GitLogEntry> changes = new TreeMap<>();
    for (Map.Entry<String, GitLogPair> entry : ChangeHelper.collectChanges(oldTree, newTree, false).entrySet()) {
      changes.put(entry.getKey(), new GitLogEntry(entry.getValue(), renames));
    }
    return changes;
  }

  private GitFile getRoot() throws IOException, SVNException {
    if (gitNewCommit == null) {
      return emptyFile();
    }
    return new GitFile(repo, gitNewCommit, revision);
  }

  @NotNull
  private GitFile emptyFile() throws IOException, SVNException {
    return new GitFile(repo, null, "", GitProperty.emptyArray, revision);
  }

  @NotNull
  @Override
  public Map<String, String> getProperties(boolean includeInternalProps) {
    final Map<String, String> props = new HashMap<>();
    if (includeInternalProps) {
      putProperty(props, SVNRevisionProperty.AUTHOR, getAuthor());
      putProperty(props, SVNRevisionProperty.LOG, getLog());
      putProperty(props, SVNRevisionProperty.DATE, getDateString());
    }
    if (gitNewCommit != null) {
      props.put(SvnConstants.PROP_GIT, gitNewCommit.name());
    }
    return props;
  }

  private void putProperty(@NotNull Map<String, String> props, @NotNull String name, @Nullable String value) {
    if (value != null) {
      props.put(name, value);
    }
  }

  @Override
  public long getDate() {
    return date;
  }

  @Nullable
  @Override
  public String getAuthor() {
    return gitNewCommit == null ? null : gitNewCommit.getCommitterIdent().getName();
  }

  @Nullable
  @Override
  public String getLog() {
    return gitNewCommit == null ? null : gitNewCommit.getFullMessage().trim();
  }

  @Nullable
  @Override
  public GitFile getFile(@NotNull String fullPath) throws IOException, SVNException {
    GitFile result = getRoot();
    for (String pathItem : fullPath.split("/")) {
      if (pathItem.isEmpty()) {
        continue;
      }
      result = result.getEntry(pathItem);
      if (result == null) {
        return null;
      }
    }
    return result;
  }

  @Nullable
  @Override
  public VcsCopyFrom getCopyFrom(@NotNull String fullPath) {
    return renames.get(fullPath);
  }
}

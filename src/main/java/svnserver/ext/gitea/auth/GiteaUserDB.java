/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.gitea.auth;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.gitea.ApiClient;
import io.gitea.ApiException;
import io.gitea.api.UserApi;
import io.gitea.model.UserSearchList;
import svnserver.auth.Authenticator;
import svnserver.auth.PlainAuthenticator;
import svnserver.auth.User;
import svnserver.auth.UserDB;
import svnserver.context.SharedContext;
import svnserver.ext.gitea.config.GiteaContext;

import java.net.HttpURLConnection;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Gitea user authentiation.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 * @author Andrew Thornton <zeripath@users.noreply.github.com>
 */
public final class GiteaUserDB implements UserDB {
  @NotNull
  private static final Logger log = LoggerFactory.getLogger(GiteaUserDB.class);

  @NotNull
  private final Collection<Authenticator> authenticators = Collections.singleton(new PlainAuthenticator(this));
  @NotNull
  private final GiteaContext context;

  GiteaUserDB(@NotNull SharedContext context) {
    this.context = context.sure(GiteaContext.class);
  }

  @NotNull
  @Override
  public Collection<Authenticator> authenticators() {
    return authenticators;
  }

  @Override
  public void updateEnvironment(@NotNull Map<String, String> env, @NotNull User userInfo) {
    final String externalId = userInfo.getExternalId();
    env.put("SSH_ORIGINAL_COMMAND", "git");
    env.put("GITEA_PUSHER_EMAIL", userInfo.getEmail());
    if (externalId != null) {
      env.put("GITEA_PUSHER_ID", userInfo.getExternalId());
    }
  }

  @Override
  public User check(@NotNull String userName, @NotNull String password) {
    try {
      final ApiClient apiClient = context.connect(userName, password);
      final UserApi userApi = new UserApi(apiClient);
      return createUser(userApi.userGetCurrent());
    } catch (ApiException e) {
      if (e.getCode() == HttpURLConnection.HTTP_UNAUTHORIZED || e.getCode() == HttpURLConnection.HTTP_FORBIDDEN) {
        return null;
      }
      log.warn("User password check error: " + userName, e);
      return null;
    }
  }

  @NotNull
  private User createUser(@NotNull io.gitea.model.User user) {
    return User.create(user.getLogin(), user.getFullName(), user.getEmail(), "" + user.getId());
  }

  @Nullable
  @Override
  public User lookupByUserName(@NotNull String userName) {
    ApiClient apiClient = context.connect();
    try {
      UserApi userApi = new UserApi(apiClient);
      io.gitea.model.User user = userApi.userGet(userName);
      return createUser(user);
    } catch (ApiException e) {
      if (e.getCode() != 404) {
        log.warn("User lookup by name error: " + userName, e);
      }
      return null;
    }
  }

  @Nullable
  @Override
  public User lookupByExternal(@NotNull String external) {
    final String userId = external;
    if (userId != null) {
      try {
        final UserApi userApi = new UserApi(context.connect());
        UserSearchList users = userApi.userSearch(null, null);
        for (io.gitea.model.User u : users.getData()) {
          if (userId.equals("" + u.getId())) {
            return createUser(u);
          }
        }
      } catch (ApiException e) {
        if (e.getCode() != 404) {
          log.warn("User lookup by userId error: " + external, e);
        }
        return null;
      }
    }
    return null;
  }
}

package io.akka.umami;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import akka.javasdk.client.ComponentClient;
import io.akka.umami.application.Store;
import io.akka.umami.domain.Accounts;
import io.akka.umami.lib.Constants;
import io.akka.umami.lib.Env;
import io.akka.umami.lib.Passwords;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What has to exist before anybody can sign in.
 *
 * <p>umami's own first-run migration inserts an administrator called {@code admin} whose password
 * is {@code umami}, and every deployment starts from there. Recreating that here is what lets the
 * two systems be driven by the same script.
 */
@Setup
public class Bootstrap implements ServiceSetup {

  private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

  private static final String DEFAULT_ADMIN_ID = "41e2b680-648e-4b09-bcd7-3e2b10c06264";

  private final ComponentClient componentClient;

  public Bootstrap(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Override
  public void onStartup() {
    var store = new Store(componentClient);
    try {
      if (store.user(DEFAULT_ADMIN_ID) != null) {
        return;
      }
      var now = Instant.now();
      store.put(
          Store.USER,
          DEFAULT_ADMIN_ID,
          new Accounts.User(
              DEFAULT_ADMIN_ID,
              "admin",
              Passwords.hashPassword(Env.get("DEFAULT_ADMIN_PASSWORD", "umami")),
              Constants.ROLE_ADMIN,
              null,
              null,
              false,
              now,
              now,
              null));
      LOG.info("created the first administrator");
    } catch (RuntimeException e) {
      // A start that cannot reach the store yet is not fatal: the account is created on the
      // next start, and every route answers a refusal until it is.
      LOG.warn("could not create the first administrator", e);
    }
  }

  @Override
  public DependencyProvider createDependencyProvider() {
    return null;
  }
}

package com.orderpilot.security.production;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Fails production startup when the runtime datasource has migration or owner authority. */
@Component
public class DatabaseLeastPrivilegeStartupValidator implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(DatabaseLeastPrivilegeStartupValidator.class);
  private static final String QUERY_FAILED = "RUNTIME_DB_PRIVILEGE_VALIDATION_QUERY_FAILED";

  private final Environment environment;
  private final JdbcTemplate jdbcTemplate;
  private final boolean flywayEnabled;

  public DatabaseLeastPrivilegeStartupValidator(
      Environment environment,
      JdbcTemplate jdbcTemplate,
      @Value("${spring.flyway.enabled:true}") boolean flywayEnabled) {
    this.environment = environment;
    this.jdbcTemplate = jdbcTemplate;
    this.flywayEnabled = flywayEnabled;
  }

  @Override
  public void afterPropertiesSet() {
    if (!ProductionLikeProfiles.isActive(environment)) {
      return;
    }
    try {
      if (!DatabaseLeastPrivilegeValidator.isPostgreSql(jdbcTemplate)) {
        reject(DatabaseLeastPrivilegeValidator.RUNTIME_DB_NON_POSTGRESQL);
      }
      List<String> reasons = DatabaseLeastPrivilegeValidator.classify(
          DatabaseLeastPrivilegeValidator.inspect(jdbcTemplate),
          flywayEnabled);
      if (!reasons.isEmpty()) {
        reject(reasons.get(0));
      }
    } catch (DataAccessException ex) {
      log.error("database least-privilege startup validation failed: reason={}", QUERY_FAILED);
      throw new IllegalStateException(QUERY_FAILED + ": production runtime database authority is unverifiable", ex);
    }
  }

  private static void reject(String reason) {
    log.error("database least-privilege startup validation failed: reason={}", reason);
    throw new IllegalStateException(reason + ": production runtime database authority is unsafe");
  }
}

package com.stockguard.user;

import com.stockguard.common.config.StockGuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Creates the first administrator from environment configuration. There is no built-in default password. */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final AuthService auth;
    private final StockGuardProperties.Admin admin;

    public AdminSeeder(AuthService auth, StockGuardProperties properties) {
        this.auth = auth;
        this.admin = properties.security().admin();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(admin.username()) || !StringUtils.hasText(admin.password())) {
            log.warn("No administrator configured: set STOCKGUARD_ADMIN_USERNAME and STOCKGUARD_ADMIN_PASSWORD.");
            return;
        }
        auth.seedAdmin(admin.username(), admin.password());
    }
}

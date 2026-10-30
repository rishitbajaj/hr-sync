package com.moveinsync.hrsync.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RequiredSecrets {

    static final String DATASOURCE_PASSWORD =
            "SPRING_DATASOURCE_PASSWORD is required. Set it in the environment or in .env. There is no default.";

    static final String OPERATOR_PASSWORD =
            "HR_SYNC_OPERATOR_PASSWORD is required. Set it in the environment or in .env. There is no default.";

    @Bean
    static BeanFactoryPostProcessor requiredSecretsCheck() {
        return new Check();
    }

    static void requireDatasourcePassword(String password, boolean suppliedByConnection) {
        if (suppliedByConnection) {
            return;
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(DATASOURCE_PASSWORD);
        }
    }

    static void requireOperatorPassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(OPERATOR_PASSWORD);
        }
    }

    static final class Check implements BeanFactoryPostProcessor, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            requireOperatorPassword(environment.getProperty("hr-sync.reconciliation.operator-password"));
            requireDatasourcePassword(
                    environment.getProperty("spring.datasource.password"),
                    hasExternalConnection(beanFactory)
            );
        }

        private static boolean hasExternalConnection(ConfigurableListableBeanFactory beanFactory) {
            for (String name : beanFactory.getBeanNamesForType(JdbcConnectionDetails.class, false, false)) {
                String className = beanFactory.getBeanDefinition(name).getBeanClassName();
                if (className == null || !className.endsWith("PropertiesJdbcConnectionDetails")) {
                    return true;
                }
            }
            return false;
        }
    }
}

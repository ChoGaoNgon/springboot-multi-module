package jp.co.htkk.api.config.mybatis;

import jp.co.htkk.api.config.mybatis.intercept.AuditInterceptor;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.boot.autoconfigure.SpringBootVFS;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@org.springframework.context.annotation.Configuration
public class MyBatisConfig {

    @Bean
    AuditInterceptor auditInterceptor() {
        return new AuditInterceptor();
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setVfs(SpringBootVFS.class); // Sets the SpringBootVFS class into SqlSessionFactoryBean
        factoryBean.setPlugins(auditInterceptor());

        // This factory bypasses Spring Boot's mybatis.* auto-config, so apply the
        // essential settings explicitly. jdbcTypeForNull=NULL is required for PostgreSQL.
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setJdbcTypeForNull(JdbcType.NULL);
        configuration.setDefaultFetchSize(100);
        factoryBean.setConfiguration(configuration);

        return factoryBean.getObject();
    }
}

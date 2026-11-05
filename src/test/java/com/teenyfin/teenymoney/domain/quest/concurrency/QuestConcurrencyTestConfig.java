package com.teenyfin.teenymoney.domain.quest.concurrency;

import com.teenyfin.teenymoney.domain.notification.mapper.MemberNotificationMapper;
import com.teenyfin.teenymoney.domain.notification.mapper.NotificationMapper;
import com.teenyfin.teenymoney.domain.notification.service.NotificationService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 동시성 측정용 최소 컨텍스트. QuestDeadlineTestConfig와 같은 이유로 RootConfig를 올리지 않는다.
 *
 * 다른 점은 두 가지다.
 * - 커넥션 풀 크기를 concurrency.pool.size로 받는다. 측정 조건(20 / 10)마다 컨텍스트를 새로 연다.
 * - FcmService 자리에 DelayedFcmService를 둔다. 승인 경합 상대인 송금·충전 매퍼도 스캔한다.
 */
@Configuration
@EnableTransactionManagement
@PropertySource("classpath:/application.properties")
@MapperScan(
        basePackages = {
                "com.teenyfin.teenymoney.domain.quest.mapper",
                "com.teenyfin.teenymoney.domain.teenyscore.mapper",
                "com.teenyfin.teenymoney.domain.member.mapper",
                "com.teenyfin.teenymoney.domain.notification.mapper",
                "com.teenyfin.teenymoney.domain.wallet.mapper",
                "com.teenyfin.teenymoney.domain.charge.mapper"
        },
        annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class QuestConcurrencyTestConfig {

    private static final String MAPPER_XML_PATTERN =
            "classpath*:com/teenyfin/teenymoney/**/mapper/*Mapper.xml";

    @Value("${jdbc.driver}")
    private String driver;
    @Value("${jdbc.url}")
    private String url;
    @Value("${jdbc.username}")
    private String username;
    @Value("${jdbc.password}")
    private String password;
    @Value("${concurrency.pool.size:20}")
    private int poolSize;

    @Autowired
    private ApplicationContext applicationContext;

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName(driver);
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        return new HikariDataSource(config);
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory() throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setConfigLocation(applicationContext.getResource("classpath:/mybatis-config.xml"));
        factory.setMapperLocations(applicationContext.getResources(MAPPER_XML_PATTERN));
        factory.setDataSource(dataSource());
        return factory.getObject();
    }

    @Bean
    public JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(dataSource());
    }

    @Bean
    public Clock clock() {
        return Clock.fixed(Instant.parse("2000-01-02T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    }

    @Bean
    public DelayedFcmService fcmService() {
        return new DelayedFcmService();
    }

    @Bean
    public NotificationService notificationService(NotificationMapper notificationMapper,
                                                   MemberNotificationMapper memberNotificationMapper,
                                                   DelayedFcmService fcmService,
                                                   ApplicationEventPublisher eventPublisher) {
        return new NotificationService(notificationMapper, memberNotificationMapper, fcmService, eventPublisher);
    }

    @Bean
    public PlatformTransactionManager transactionManager() {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource());
        manager.setNestedTransactionAllowed(true);
        return manager;
    }
}

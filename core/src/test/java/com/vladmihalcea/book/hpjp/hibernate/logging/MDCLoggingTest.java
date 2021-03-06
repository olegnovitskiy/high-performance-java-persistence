package com.vladmihalcea.book.hpjp.hibernate.logging;

import com.vladmihalcea.book.hpjp.util.AbstractPostgreSQLIntegrationTest;
import com.vladmihalcea.book.hpjp.util.AbstractTest;
import com.vladmihalcea.book.hpjp.util.exception.ExceptionUtil;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.Session;
import org.hibernate.exception.LockAcquisitionException;
import org.jboss.logging.MDC;
import org.junit.Test;

import javax.persistence.*;
import java.util.Properties;

import static org.junit.Assert.assertTrue;

/**
 * @author Vlad Mihalcea
 */
public class MDCLoggingTest extends AbstractPostgreSQLIntegrationTest {

    @Override
    protected Class<?>[] entities() {
        return new Class<?>[] {
            Post.class
        };
    }

    @Override
    protected void additionalProperties(Properties properties) {
        properties.put( "hibernate.jdbc.batch_size", "5" );
    }

    @Override
    protected boolean proxyDataSource() {
        return false;
    }

    @Override
    protected void afterInit() {
        doInJPA(entityManager -> {
            Post post = new Post();
            post.setId( 1L );
            post.setTitle( "Post it!" );

            entityManager.persist(post);
        });
    }

    @Test
    public void testWithoutMDC() {
        doInJPA(entityManager -> {
            Post post = entityManager.createQuery(
                "select p " +
                "from Post p " +
                "where p.id = :id", Post.class)
            .setParameter("id", 1L)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getSingleResult();

            try {
                executeSync(() -> {
                    doInJPA(_entityManager -> {
                        Post _post = _entityManager.find(Post.class, 1L);

                        _entityManager.unwrap(Session.class)
                        .buildLockRequest(
                            new LockOptions()
                                .setTimeOut(LockOptions.NO_WAIT))
                                .setLockMode(LockMode.PESSIMISTIC_WRITE)
                        .lock(_post);
                    });
                });
            } catch (Exception expected) {
                assertTrue(ExceptionUtil.rootCause(expected).getMessage().contains("could not obtain lock on row in relation"));
            }
        });
    }

    @Test
    public void testWithMDC() {
        try {
            doInJPA(entityManager -> {
                MDC.put("txId", String.format(" TxId: [%s]", transactionId(entityManager)));

                Post post = entityManager.createQuery(
                    "select p " +
                    "from Post p " +
                    "where p.id = :id", Post.class)
                .setParameter("id", 1L)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();

                try {
                    executeSync(() -> {
                        try {
                            doInJPA(_entityManager -> {
                                MDC.put("txId", String.format(" TxId: [%s]", transactionId(_entityManager)));

                                Post _post = _entityManager.find(Post.class, 1L);

                                _entityManager.unwrap(Session.class)
                                .buildLockRequest(
                                    new LockOptions()
                                        .setTimeOut(LockOptions.NO_WAIT))
                                        .setLockMode(LockMode.PESSIMISTIC_WRITE)
                                .lock(_post);
                            });
                        } finally {
                            MDC.remove("txId");
                        }
                    });
                } catch (Exception expected) {
                    assertTrue(ExceptionUtil.rootCause(expected).getMessage().contains("could not obtain lock on row in relation"));
                }
            });
        } finally {
            MDC.remove("txId");
        }
    }

    private String transactionId(EntityManager entityManager) {
        return (String) entityManager.createNativeQuery(
                "SELECT CAST(txid_current() AS text) ")
                .getSingleResult();
    }

    @Entity(name = "Post")
    @Table(name = "post")
    public static class Post {

        @Id
        private Long id;

        private String title;

        @Version
        private int version;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }
}

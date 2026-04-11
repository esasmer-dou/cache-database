package com.reactor.cachedb.examples;

import com.reactor.cachedb.core.api.CacheSession;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.cache.CachePolicy;
import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.model.EntityCodec;
import com.reactor.cachedb.core.model.EntityMetadata;
import com.reactor.cachedb.core.plan.FetchPlan;
import com.reactor.cachedb.core.query.QuerySpec;
import com.reactor.cachedb.examples.entity.GeneratedCacheModule;
import com.reactor.cachedb.examples.entity.UserEntity;
import com.reactor.cachedb.examples.entity.UserEntityCacheBinding;
import com.reactor.cachedb.starter.GeneratedCacheBindingsDiscovery;
import com.reactor.cachedb.starter.GeneratedCacheBindingsRegistrar;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedBindingDeclarativeHelpersTest {

    @Test
    void shouldExposeNamedQueryFetchPresetPagePresetAndWriteHelpers() {
        QuerySpec activeUsers = UserEntityCacheBinding.activeUsersQuery(12);
        assertEquals(12, activeUsers.limit());
        assertEquals("status", activeUsers.filters().get(0).column());
        assertEquals("ACTIVE", activeUsers.filters().get(0).value());

        FetchPlan ordersPreview = UserEntityCacheBinding.ordersPreviewFetchPlan(6);
        assertEquals(6, ordersPreview.relationLimit("orders"));

        assertEquals(2, UserEntityCacheBinding.usersPageWindow(2, 25).pageNumber());
        assertEquals(25, UserEntityCacheBinding.usersPageWindow(2, 25).pageSize());

        UserEntity activated = UserEntityCacheBinding.activateUserCommand(41L, "alice");
        assertEquals(41L, activated.id);
        assertEquals("alice", activated.username);
        assertEquals("ACTIVE", activated.status);

        assertEquals(41L, UserEntityCacheBinding.deleteUserId(41L));
    }

    @Test
    void shouldExposeSessionBoundUseCaseGroups() {
        CapturingUserSession session = new CapturingUserSession();

        QuerySpec activeUsers = UserEntityCacheBinding.using(session).queries().activeUsersQuery(7);
        assertEquals(7, activeUsers.limit());

        UserEntityCacheBinding.using(session).queries().activeUsers(7);
        assertEquals(7, session.repository.lastQuery.limit());

        FetchPlan ordersPreview = UserEntityCacheBinding.using(session).fetches().ordersPreviewFetchPlan(4);
        assertEquals(4, ordersPreview.relationLimit("orders"));

        UserEntityCacheBinding.using(session).fetches().ordersPreview(4);
        assertEquals(4, session.repository.lastFetchPlan.relationLimit("orders"));

        PageWindow usersPageWindow = UserEntityCacheBinding.using(session).pages().usersPageWindow(2, 25);
        assertEquals(2, usersPageWindow.pageNumber());
        assertEquals(25, usersPageWindow.pageSize());

        UserEntityCacheBinding.using(session).pages().usersPage(2, 25);
        assertEquals(2, session.repository.lastPage.pageNumber());
        assertEquals(25, session.repository.lastPage.pageSize());

        UserEntity activated = UserEntityCacheBinding.using(session).commands().activateUser(84L, "bob");
        assertEquals(84L, activated.id);
        assertEquals("ACTIVE", session.repository.lastSaved.status);

        UserEntityCacheBinding.using(session).deletes().deleteUser(84L);
        assertEquals(84L, session.repository.lastDeletedId);
    }

    @Test
    void shouldPublishGeneratedRegistrarsForZeroGlueStartup() {
        List<GeneratedCacheBindingsRegistrar> registrars =
                GeneratedCacheBindingsDiscovery.discover(GeneratedBindingDeclarativeHelpersTest.class.getClassLoader());

        assertTrue(registrars.stream().anyMatch(registrar -> registrar.packageName().equals("com.reactor.cachedb.examples.entity")));
        assertTrue(registrars.stream().anyMatch(registrar -> registrar.packageName().equals("com.reactor.cachedb.examples.demo.entity")));
    }

    @Test
    void shouldExposePackageLevelDomainModule() {
        CapturingUserSession session = new CapturingUserSession();

        QuerySpec activeUsers = GeneratedCacheModule.using(session).users().queries().activeUsersQuery(9);
        assertEquals(9, activeUsers.limit());

        GeneratedCacheModule.using(session).users().queries().activeUsers(9);
        assertEquals(9, session.repository.lastQuery.limit());

        GeneratedCacheModule.using(session).users().commands().activateUser(11L, "zoe");
        assertEquals(11L, session.repository.lastSaved.id);

        GeneratedCacheModule.using(session).users().deletes().deleteUser(11L);
        assertEquals(11L, session.repository.lastDeletedId);
    }

    private static final class CapturingUserSession implements CacheSession {
        private final CapturingUserRepository repository = new CapturingUserRepository();

        @Override
        @SuppressWarnings("unchecked")
        public <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> metadata, EntityCodec<T> codec) {
            return (EntityRepository<T, ID>) repository;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T, ID> EntityRepository<T, ID> repository(EntityMetadata<T, ID> metadata, EntityCodec<T> codec, CachePolicy cachePolicy) {
            return (EntityRepository<T, ID>) repository;
        }
    }

    private static final class CapturingUserRepository implements EntityRepository<UserEntity, Long> {
        private QuerySpec lastQuery = QuerySpec.builder().build();
        private FetchPlan lastFetchPlan = FetchPlan.empty();
        private PageWindow lastPage = new PageWindow(0, 0);
        private UserEntity lastSaved;
        private Long lastDeletedId;

        @Override
        public Optional<UserEntity> findById(Long id) {
            return Optional.empty();
        }

        @Override
        public List<UserEntity> findAll(Collection<Long> ids) {
            return List.of();
        }

        @Override
        public List<UserEntity> findPage(PageWindow pageWindow) {
            this.lastPage = pageWindow;
            return List.of();
        }

        @Override
        public com.reactor.cachedb.core.query.QueryExplainPlan explain(QuerySpec querySpec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UserEntity> query(QuerySpec querySpec) {
            this.lastQuery = querySpec;
            return List.of();
        }

        @Override
        public UserEntity save(UserEntity entity) {
            this.lastSaved = entity;
            return entity;
        }

        @Override
        public void deleteById(Long id) {
            this.lastDeletedId = id;
        }

        @Override
        public EntityRepository<UserEntity, Long> withFetchPlan(FetchPlan fetchPlan) {
            this.lastFetchPlan = fetchPlan;
            return this;
        }
    }
}

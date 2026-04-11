package com.reactor.cachedb.examples.loader;

import com.reactor.cachedb.core.cache.PageWindow;
import com.reactor.cachedb.core.page.EntityPageLoader;
import com.reactor.cachedb.examples.entity.UserEntity;

import java.util.ArrayList;
import java.util.List;

public final class UserPageLoader implements EntityPageLoader<UserEntity> {
    @Override
    public List<UserEntity> load(PageWindow pageWindow) {
        List<UserEntity> users = new ArrayList<>();
        for (int index = 0; index < pageWindow.pageSize(); index++) {
            UserEntity entity = new UserEntity();
            entity.id = (long) (pageWindow.offset() + index + 1);
            entity.username = "user-" + entity.id;
            entity.status = "PAGED";
            users.add(entity);
        }
        return users;
    }
}

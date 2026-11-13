package com.example.cachedb.sample.encryption;

import com.reactor.cachedb.annotations.CacheRepository;
import com.reactor.cachedb.core.repository.CacheDbRepository;

@CacheRepository(entity = EncryptedNote.class, springBean = false)
public interface EncryptedNotes extends CacheDbRepository<EncryptedNote, String> {}

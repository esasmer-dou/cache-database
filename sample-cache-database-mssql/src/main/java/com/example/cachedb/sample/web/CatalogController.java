package com.example.cachedb.sample.web;

import com.example.cachedb.sample.catalog.CatalogCard;
import com.example.cachedb.sample.catalog.CatalogLookup;
import com.reactor.cachedb.core.repository.CursorPage;
import com.reactor.cachedb.core.repository.WindowRequest;
import com.reactor.cachedb.spring.boot.snapshot.TypedSnapshotRepository;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final TypedSnapshotRepository<Long, CatalogCard> catalog;

    public CatalogController(TypedSnapshotRepository<Long, CatalogCard> catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<CatalogLookup> item(@PathVariable @Positive long id) {
        var value = catalog.lookup(id);
        int status = !value.isSnapshotAvailable() ? 503 : value.isPresent() ? 200 : 404;
        return ResponseEntity.status(status).body(CatalogLookup.from(value));
    }

    @GetMapping("/items")
    public Map<Long, CatalogLookup> items(
            @RequestParam @Size(min = 1, max = 100) List<@Positive Long> ids) {
        var result = new LinkedHashMap<Long, CatalogLookup>();
        catalog.lookupAll(ids).forEach((id, value) -> result.put(id, CatalogLookup.from(value)));
        return result;
    }

    @GetMapping("/categories/{category}")
    public CursorPage<CatalogLookup> category(
            @PathVariable @Size(min = 1, max = 64) String category,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Size(max = 8192) String after) {
        return catalog.findTypedPage(category, WindowRequest.of(limit, after))
                .map(CatalogLookup::from);
    }
}

package com.example.cachedb.sample.cacheapi;

import com.example.cachedb.sample.controls.ControlsAccess;
import com.example.cachedb.sample.domain.SupplierOffer;
import com.example.cachedb.sample.domain.TenantStock;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.repository.HotLookup;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/examples/v019")
@ConditionalOnProperty(prefix = "sample.cache-api", name = "enabled", havingValue = "true")
public class CacheApiController {
    private static final java.util.regex.Pattern SKU = java.util.regex.Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,31}");
    private final ControlsAccess access;
    private final MethodCacheService cache;
    private final CacheApiOperations operations;
    public CacheApiController(ControlsAccess access, MethodCacheService cache, CacheApiOperations operations) {
        this.access = access; this.cache = cache; this.operations = operations;
    }
    public record Quote(String tenant, String sku, int branch, String value) {}
    public record OfferInput(@NotBlank @Size(max = 32) String supplier,
            @NotNull @Pattern(regexp = "ACTIVE|PAUSED") String status,
            @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
            @NotNull @Min(0) @Max(1000000) Integer availableUnits) {
        SupplierOffer entity(long id) { return new SupplierOffer(id, supplier, status, unitPrice, availableUnits); }
    }
    public record Accepted(SupplierOffer entity, Long id, long version, String causalToken,
            boolean redisAccepted, boolean sqlDurabilityConfirmed) {}

    @GetMapping("/cache/quotes/{sku}")
    public Quote quote(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String sku, @RequestParam(defaultValue = "0") int branch,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") String token) {
        String tenant = access.tenant(auth); key(sku, branch);
        return cached(tenant, token, () -> new Quote(tenant, sku, branch, cache.quote(sku, branch)));
    }
    @PostMapping("/cache/quotes/{sku}/refresh")
    public Quote refresh(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String sku, @RequestParam(defaultValue = "0") int branch,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") String token) {
        String tenant = access.tenant(auth); key(sku, branch);
        return cached(tenant, token, () -> new Quote(tenant, sku, branch, cache.refresh(sku, branch)));
    }
    @DeleteMapping("/cache/quotes/{sku}")
    public ResponseEntity<Void> evict(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String sku, @RequestParam(defaultValue = "0") int branch,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") String token) {
        String tenant = access.tenant(auth); key(sku, branch);
        return cached(tenant, token, () -> { cache.evict(sku, branch); return ResponseEntity.noContent().build(); });
    }
    @DeleteMapping("/cache/quotes")
    public ResponseEntity<Void> clear(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") String token) {
        String tenant = access.tenant(auth);
        return cached(tenant, token, () -> { cache.clear(); return ResponseEntity.noContent().build(); });
    }
    @GetMapping("/cache/nullable/{sku}")
    public Quote nullable(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String sku,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") String token) {
        String tenant = access.tenant(auth); key(sku, 0);
        return cached(tenant, token, () -> new Quote(tenant, sku, 0, cache.nullable(sku)));
    }

    @PutMapping("/async/offers/{id}")
    public CompletableFuture<ResponseEntity<Accepted>> save(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id, @Valid @RequestBody OfferInput input,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") String token) {
        access.supplier(auth); positive(id); token(token);
        try (var context = CacheCausalContext.withToken(token)) {
            return deadline(operations.save(input.entity(id))).thenApply(CacheApiController::accepted);
        }
    }
    @GetMapping("/async/offers/{id}")
    public CompletableFuture<ResponseEntity<SupplierOffer>> hot(
            @RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long id,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") String token) {
        access.supplier(auth); positive(id); token(token);
        try (var context = CacheCausalContext.withToken(token)) {
            return deadline(operations.hot(id)).thenApply(row -> ResponseEntity.ok(requireHot(row)));
        }
    }
    @GetMapping("/async/offers/{id}/source")
    public CompletableFuture<ResponseEntity<SupplierOffer>> source(
            @RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long id,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") String token) {
        access.supplier(auth); positive(id); token(token);
        if (!token.isEmpty() || CacheCausalContext.hasRequirements())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Entity causal tokens do not prove source durability");
        return deadline(operations.source(id)).thenApply(row -> ResponseEntity.ok(row.orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Offer is not in the source database"))));
    }

    @GetMapping("/reactive/stock/{id}")
    public CompletableFuture<ResponseEntity<TenantStock>> stock(
            @RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long id,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") String token) {
        String tenant = access.tenant(auth); positive(id);
        String required = requiredToken(token);
        return deadline(operations.stock(id, tenant, required).toFuture())
                .thenApply(row -> ResponseEntity.ok(requireHot(row)));
    }
    @GetMapping("/reactive/stock")
    public CompletableFuture<ResponseEntity<List<TenantStock>>> stocks(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam List<Long> ids,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") String token) {
        String tenant = access.tenant(auth);
        if (ids == null || ids.isEmpty() || ids.size() > 32 || ids.stream().anyMatch(id -> id == null || id < 1))
            throw new IllegalArgumentException("Supply 1..32 positive stock IDs");
        return deadline(operations.stocks(ids, tenant, requiredToken(token)).collectList().toFuture())
                .thenApply(ResponseEntity::ok);
    }

    private static <T> T cached(String tenant, String token, Supplier<T> work) {
        token(token);
        if (!token.isEmpty() || CacheCausalContext.hasRequirements())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Method caches cannot satisfy entity causal tokens");
        try (var scope = CacheTenantContext.withTenant(tenant)) { return work.get(); }
    }
    private static String requiredToken(String token) {
        token(token);
        try (var context = CacheCausalContext.withToken(token)) { return CacheCausalContext.encodeToken(); }
    }
    private static void token(String token) {
        if (token == null || token.length() > 4096) throw new IllegalArgumentException("Causal token exceeds 4096 characters");
    }
    private static void positive(long id) {
        if (id < 1) throw new IllegalArgumentException("ID must be positive");
    }
    private static void key(String sku, int branch) {
        if (sku == null || sku.length() > 32 || !SKU.matcher(sku).matches()
                || branch < 0 || branch > 999)
            throw new IllegalArgumentException("Use a 1..32 character SKU and branch in 0..999");
    }
    private static <T> T requireHot(HotLookup<T> row) {
        return row.orElseThrow(status -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Redis hot lookup is " + status + "; use an explicit warm/source route"));
    }
    private static <T> CompletableFuture<T> deadline(CompletableFuture<T> operation) {
        return operation.copy().orTimeout(3, TimeUnit.SECONDS);
    }
    private static ResponseEntity<Accepted> accepted(WriteReceipt<SupplierOffer, Long> receipt) {
        if (receipt.version() < 1 || receipt.entity() == null || receipt.redisNamespace() == null)
            throw new IllegalStateException("A real accepted write receipt is required");
        String token;
        try (var empty = CacheCausalContext.empty();
             var context = CacheCausalContext.withReceipt(receipt.redisNamespace(), receipt)) {
            token = CacheCausalContext.encodeToken();
        }
        return ResponseEntity.accepted().header("X-CacheDB-Causal", token)
                .body(new Accepted(receipt.entity(), receipt.id(), receipt.version(), token, true, false));
    }
}

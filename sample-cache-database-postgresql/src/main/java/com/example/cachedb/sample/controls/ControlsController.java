package com.example.cachedb.sample.controls;

import com.example.cachedb.sample.domain.*;
import com.example.cachedb.sample.readmodel.TenantStockCard;
import com.reactor.cachedb.core.causal.CacheCausalContext;
import com.reactor.cachedb.core.exception.CacheTenantAccessDeniedException;
import com.reactor.cachedb.core.model.WriteReceipt;
import com.reactor.cachedb.core.tenant.CacheTenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/examples/v018")
@ConditionalOnProperty(prefix = "sample.demo", name = "write-tools-enabled", havingValue = "true")
@Validated
public class ControlsController {
    private final ControlsAccess access;
    private final ControlsStore store;
    private final com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor jobs;
    public ControlsController(ControlsAccess access, ControlsStore store,
            com.reactor.cachedb.spring.boot.CacheDistributedJobExecutor jobs) {
        this.access = access; this.store = store; this.jobs = jobs;
    }

    @PostMapping("/offers/warm")
    public ResponseEntity<com.reactor.cachedb.spring.boot.CacheDistributedJobSnapshot> warm(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody OfferWarmCommand command) {
        access.supplier(auth);
        return ResponseEntity.accepted().body(jobs.submit(OfferWarmHandler.DEFINITION, command));
    }
    @GetMapping("/offers/{id}/source")
    public SupplierOffer source(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id) {
        access.supplier(auth); return store.source(id);
    }

    public record OfferInput(@NotBlank @Size(max = 32) String supplier,
            @NotBlank @Pattern(regexp = "ACTIVE|PAUSED") String status,
            @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
            @NotNull @Min(0) @Max(1_000_000) Integer availableUnits) {
        public SupplierOffer entity(long id) { return new SupplierOffer(id, supplier, status, unitPrice, availableUnits); }
    }
    public record StockInput(@NotNull @Positive Long stockId, @NotBlank @Size(max = 32) String warehouse,
            @NotBlank @Pattern(regexp = "AVAILABLE|HELD") String status,
            @NotNull @Min(0) @Max(1_000_000) Integer availableUnits) {
        public TenantStock entity(String tenant) { return new TenantStock(stockId, tenant, warehouse, status, availableUnits); }
    }
    public record Units(@NotNull @Min(0) @Max(1_000_000) Integer availableUnits) {}
    public record Changes(@NotNull @Positive Long putId, @NotNull @Valid OfferInput put,
            @NotNull @Positive Long patchId, @NotNull @Min(0) @Max(1_000_000) Integer units,
            @NotNull @Positive Long secondPutId, @NotNull @Valid OfferInput secondPut,
            @NotNull @Positive Long secondPatchId, @NotNull @Min(0) @Max(1_000_000) Integer secondUnits,
            @NotNull @Positive Long deleteId) {}
    public record Accepted(Object id, long version, String causalToken, boolean redisAccepted, boolean sqlDurabilityConfirmed) {}
    public record BatchAccepted(List<Long> versions, boolean redisAccepted, boolean sqlDurabilityConfirmed) {}

    @PutMapping("/offers/{id}")
    public ResponseEntity<Accepted> put(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id, @Valid @RequestBody OfferInput input) {
        access.supplier(auth); return accepted(store.saveOffer(input.entity(id)));
    }
    @PatchMapping("/offers/{id}/units")
    public ResponseEntity<Accepted> patch(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id, @Valid @RequestBody Units input) {
        access.supplier(auth); return accepted(store.replaceUnits(id, input.availableUnits()));
    }
    @GetMapping("/offers/{id}")
    public SupplierOffer offer(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") @Size(max = 4096) String token) {
        access.supplier(auth); return causal(token, () -> store.offer(id));
    }
    @GetMapping("/offers")
    public List<SupplierOffer> bulk(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam @Size(min = 1, max = 64) List<@NotNull @Positive Long> ids) {
        access.supplier(auth); return store.bulk(ids);
    }
    @GetMapping("/offers/search")
    public List<SupplierOffer> search(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam @NotBlank @Size(max = 32) String supplier,
            @RequestParam @Pattern(regexp = "ACTIVE|PAUSED") String status) {
        access.supplier(auth); return store.search(supplier, status);
    }
    @GetMapping("/offers/dashboard")
    public ControlsStore.Dashboard dashboard(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam @NotBlank @Size(max = 32) String supplier) {
        access.supplier(auth); return store.dashboard(supplier);
    }
    @PostMapping("/offers/changes")
    public ResponseEntity<BatchAccepted> changes(@RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody Changes input) {
        access.supplier(auth);
        if (new HashSet<>(List.of(input.putId(), input.patchId(), input.secondPutId(), input.secondPatchId(), input.deleteId())).size() != 5)
            throw new IllegalArgumentException("Mixed changes require five distinct IDs");
        var receipt = store.changes(input.put().entity(input.putId()), input.patchId(), input.units(),
                input.secondPut().entity(input.secondPutId()), input.secondPatchId(), input.secondUnits(), input.deleteId());
        return ResponseEntity.accepted().body(new BatchAccepted(receipt.versions(), true, false));
    }
    @PostMapping("/stock/batch")
    public ResponseEntity<BatchAccepted> stockBatch(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody @Size(min = 1, max = 32) List<@NotNull @Valid StockInput> input) {
        String tenant = access.tenant(auth);
        if (input.stream().map(StockInput::stockId).distinct().count() != input.size())
            throw new IllegalArgumentException("Stock batch IDs must be distinct");
        return tenant(tenant, () -> {
            var receipt = store.stockBatch(input.stream().map(row -> row.entity(tenant)).toList());
            return ResponseEntity.accepted().body(new BatchAccepted(receipt.versions(), true, false));
        });
    }
    @PatchMapping("/stock/{id}/units")
    public ResponseEntity<Accepted> stockPatch(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id, @RequestBody @Valid Units input) {
        return tenant(access.tenant(auth), () -> accepted(store.patchStock(id, input.availableUnits())));
    }
    @GetMapping("/stock/{id}")
    public TenantStock stock(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id,
            @RequestHeader(value = "X-CacheDB-Causal", defaultValue = "") @Size(max = 4096) String token) {
        return tenant(access.tenant(auth), () -> causal(token, () -> store.stock(id)));
    }
    @PutMapping("/stock/{id}")
    public ResponseEntity<Accepted> stockPut(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable @Positive long id, @RequestBody @Valid StockInput input) {
        if (id != input.stockId()) throw new IllegalArgumentException("Path and body IDs must match");
        String tenant = access.tenant(auth);
        return tenant(tenant, () -> accepted(store.saveStock(input.entity(tenant))));
    }
    @GetMapping("/stock/top")
    public Map<String, List<TenantStock>> top(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam @Size(min = 1, max = 8) List<@NotBlank @Size(max = 32) String> warehouses,
            @RequestParam(defaultValue = "2") @Min(1) @Max(10) int limit) {
        return tenant(access.tenant(auth), () -> store.top(warehouses, limit));
    }
    @GetMapping("/stock/cards")
    public List<TenantStockCard> cards(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return tenant(access.tenant(auth), () -> store.cards(limit));
    }
    private static ResponseEntity<Accepted> accepted(WriteReceipt<?, ?> receipt) {
        String token = ControlsStore.token(receipt);
        return ResponseEntity.accepted().header("X-CacheDB-Causal", token)
                .body(new Accepted(receipt.id(), receipt.version(), token, true, false));
    }
    private static <T> T causal(String token, Supplier<T> read) {
        try (var scope = CacheCausalContext.withToken(token)) { return read.get(); }
        catch (IllegalStateException failure) {
            if (failure.getMessage() != null && failure.getMessage().startsWith("Causal "))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Required Redis version is unavailable", failure);
            throw failure;
        }
    }
    private static <T> T tenant(String tenant, Supplier<T> work) {
        try (var scope = CacheTenantContext.withTenant(tenant)) { return work.get(); }
        catch (CacheTenantAccessDeniedException denied) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant access denied", denied);
        } catch (IllegalStateException denied) {
            if (denied.getMessage() != null && denied.getMessage().startsWith("Atomic batch") && denied.getMessage().contains("tenant"))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant batch denied", denied);
            throw denied;
        }
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ProblemDetail> notCached(NoSuchElementException missing) {
        return ResponseEntity.status(404).body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, missing.getMessage()));
    }
}

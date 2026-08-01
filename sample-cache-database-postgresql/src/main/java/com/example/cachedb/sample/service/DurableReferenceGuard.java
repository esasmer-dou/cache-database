package com.example.cachedb.sample.service;

import com.example.cachedb.sample.domain.GeneratedCacheModule;
import com.reactor.cachedb.core.api.EntityRepository;
import com.reactor.cachedb.core.model.WriteDependency;
import com.reactor.cachedb.core.model.WriteReceipt;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.BooleanSupplier;

@Service
public final class DurableReferenceGuard {

    private final GeneratedCacheModule.Scope domain;

    public DurableReferenceGuard(GeneratedCacheModule.Scope domain) {
        this.domain = domain;
    }

    public DurableReference requireCustomer(long customerId) {
        return resolve(
                "Customer",
                "customerId",
                customerId,
                domain.customers().dependency(customerId),
                () -> domain.customers().source().findById(customerId).isPresent()
        );
    }

    public DurableReference requireOrder(long orderId) {
        return resolve(
                "Order",
                "orderId",
                orderId,
                domain.orders().dependency(orderId),
                () -> domain.orders().source().findById(orderId).isPresent()
        );
    }

    public DurableReference requireShipment(long shipmentId) {
        return resolve(
                "Shipment",
                "shipmentId",
                shipmentId,
                domain.shipments().dependency(shipmentId),
                () -> domain.shipments().source().findById(shipmentId).isPresent()
        );
    }

    private DurableReference resolve(
            String entityName,
            String idName,
            long id,
            Optional<WriteDependency> activeDependency,
            BooleanSupplier durableSourceLookup
    ) {
        if (activeDependency.isPresent()) {
            return new DurableReference(activeDependency.orElseThrow());
        }
        if (durableSourceLookup.getAsBoolean()) {
            return new DurableReference(null);
        }
        throw new DurableReferenceUnavailableException(
                entityName + " " + idName + "=" + id
                        + " does not exist in the active Redis set or durable SQL source."
        );
    }

    /**
     * A hot parent carries a write-behind dependency. A cold parent that already
     * exists in SQL needs no queue dependency and can accept the child directly.
     */
    public record DurableReference(WriteDependency pendingDependency) {

        public <T, ID> WriteReceipt<T, ID> save(EntityRepository<T, ID> repository, T entity) {
            return pendingDependency == null
                    ? repository.saveWithReceipt(entity)
                    : repository.saveAfter(entity, pendingDependency);
        }
    }
}

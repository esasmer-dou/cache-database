package com.example.cachedb.sample.service;

import com.example.cachedb.sample.repository.CustomerRepository;
import com.example.cachedb.sample.repository.OrderRepository;
import com.example.cachedb.sample.repository.ShipmentRepository;
import com.reactor.cachedb.core.repository.CacheDbRepository;
import com.reactor.cachedb.core.model.WriteDependency;
import com.reactor.cachedb.core.model.WriteReceipt;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.BooleanSupplier;

@Service
public final class DurableReferenceGuard {

    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final ShipmentRepository shipments;

    public DurableReferenceGuard(
            CustomerRepository customers,
            OrderRepository orders,
            ShipmentRepository shipments
    ) {
        this.customers = customers;
        this.orders = orders;
        this.shipments = shipments;
    }

    public DurableReference requireCustomer(long customerId) {
        return resolve(
                "Customer",
                "customerId",
                customerId,
                customers.dependency(customerId),
                () -> customers.findSourceById(customerId).isPresent()
        );
    }

    public DurableReference requireOrder(long orderId) {
        return resolve(
                "Order",
                "orderId",
                orderId,
                orders.dependency(orderId),
                () -> orders.findSourceById(orderId).isPresent()
        );
    }

    public DurableReference requireShipment(long shipmentId) {
        return resolve(
                "Shipment",
                "shipmentId",
                shipmentId,
                shipments.dependency(shipmentId),
                () -> shipments.findSourceById(shipmentId).isPresent()
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

        public <T, ID> WriteReceipt<T, ID> save(CacheDbRepository<T, ID> repository, T entity) {
            return pendingDependency == null
                    ? repository.save(entity)
                    : repository.saveAfter(entity, pendingDependency);
        }
    }
}

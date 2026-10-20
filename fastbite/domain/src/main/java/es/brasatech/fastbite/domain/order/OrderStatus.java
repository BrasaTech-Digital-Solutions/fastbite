package es.brasatech.fastbite.domain.order;

/** Stored by ordinal: never reorder or insert values; append only. */
public enum OrderStatus {
    CREATED, ACCEPTED, PROCESSING, DONE, DELIVERED, COMPLETE, CANCELLED;
}

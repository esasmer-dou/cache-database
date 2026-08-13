package com.reactor.cachedb.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A compile-time validated predicate over a Java entity field. */
@Target({})
@Retention(RetentionPolicy.SOURCE)
public @interface CachePredicate {
    String field();

    Operator operator() default Operator.EQ;

    /**
     * Method parameter that supplies the value. When both this value and
     * constants are empty, the processor infers a compatible parameter whose
     * name matches field.
     */
    String parameter() default "";

    String[] constants() default {};

    ConstantType constantType() default ConstantType.STRING;

    /** Predicates in the same group are ANDed; distinct groups are ORed. */
    int group() default 0;

    enum Operator {
        EQ,
        NE,
        GT,
        GTE,
        LT,
        LTE,
        IN,
        CONTAINS,
        STARTS_WITH
    }

    enum ConstantType {
        STRING,
        INTEGER,
        LONG,
        DOUBLE,
        DECIMAL,
        BOOLEAN
    }
}

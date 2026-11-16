package org.example.md05_session07_homework.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String dishName;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    protected Order() {
    }

    public Order(String customerName, String dishName, BigDecimal totalAmount) {
        this.customerName = customerName;
        this.dishName = dishName;
        this.totalAmount = totalAmount;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getDishName() {
        return dishName;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
}

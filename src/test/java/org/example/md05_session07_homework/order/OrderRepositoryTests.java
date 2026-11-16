package org.example.md05_session07_homework.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderRepositoryTests {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    @Test
    void connectsToPostgreSql() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
    }

    @Test
    void savesAndFindsOrderById() {
        Order saved = orderRepository.save(new Order("An", "Pho bo", new BigDecimal("55000.00")));

        assertThat(saved.getId()).isNotNull();
        assertThat(orderRepository.findById(saved.getId()))
                .hasValueSatisfying(order -> {
                    assertThat(order.getDishName()).isEqualTo("Pho bo");
                    assertThat(order.getTotalAmount()).isEqualByComparingTo("55000");
                });
    }

    @Test
    void findsOrdersByCustomerName() {
        orderRepository.save(new Order("Binh", "Com tam", new BigDecimal("45000")));
        orderRepository.save(new Order("Binh", "Tra da", new BigDecimal("5000")));
        orderRepository.save(new Order("Chi", "Bun cha", new BigDecimal("50000")));

        List<Order> orders = orderRepository.findByCustomerName("Binh");

        assertThat(orders).hasSize(2)
                .extracting(Order::getDishName)
                .containsExactlyInAnyOrder("Com tam", "Tra da");
    }
}

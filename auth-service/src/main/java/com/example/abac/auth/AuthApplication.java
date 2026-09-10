package com.example.abac.auth;

import com.example.abac.auth.repository.UserRepository;
import com.example.abac.auth.service.AuthService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

/**
 * 认证服务。播种 4 个属性差异明显的账号，用来演示同一条策略在不同属性下给出不同裁决：
 *   admin   dept=EXEC  clearance=5 region=CN title=admin      —— 高密级 + 管理员
 *   carol   dept=ENG   clearance=4 region=US title=manager    —— 经理，但地区在境外
 *   alice   dept=ENG   clearance=3 region=CN title=engineer   —— 普通工程师
 *   bob     dept=SALES clearance=2 region=CN title=sales      —— 跨部门 + 低密级
 */
@SpringBootApplication
@EnableDiscoveryClient
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }

    @Bean
    CommandLineRunner seed(UserRepository userRepository, AuthService authService) {
        return args -> {
            seed(userRepository, authService, "admin", "admin123", "EXEC", 5, "CN", "admin");
            seed(userRepository, authService, "carol", "carol123", "ENG", 4, "US", "manager");
            seed(userRepository, authService, "alice", "alice123", "ENG", 3, "CN", "engineer");
            seed(userRepository, authService, "bob", "bob123", "SALES", 2, "CN", "sales");
        };
    }

    private void seed(UserRepository repo, AuthService authService, String username, String password,
                      String dept, int clearance, String region, String title) {
        if (repo.findByUsername(username).isEmpty()) {
            authService.register(username, password, dept, clearance, region, title);
            System.out.println("[auth] seeded " + username + " / " + password
                    + " (dept=" + dept + " clearance=" + clearance + " region=" + region + " title=" + title + ")");
        }
    }
}

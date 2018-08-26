package com.example.authservice;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.cassandra.config.AbstractReactiveCassandraConfiguration;
import org.springframework.data.cassandra.config.SchemaAction;
import org.springframework.data.cassandra.core.cql.keyspace.CreateKeyspaceSpecification;
import org.springframework.data.cassandra.core.cql.keyspace.DropKeyspaceSpecification;
import org.springframework.data.cassandra.core.cql.keyspace.KeyspaceOption;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.cassandra.repository.ReactiveCassandraRepository;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebSession;
import org.springframework.web.server.session.HeaderWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

import static java.util.stream.Collectors.toList;
import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.badRequest;
import static org.springframework.web.reactive.function.server.ServerResponse.noContent;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;


@SpringBootApplication
@EnableDiscoveryClient
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    WebSessionIdResolver webSessionIdResolver() {
        HeaderWebSessionIdResolver webSessionIdResolver = new HeaderWebSessionIdResolver();
        webSessionIdResolver.setHeaderName("X-AUTH-TOKEN");
        return webSessionIdResolver;
    }

    @Bean
    SecurityWebFilterChain springWebFilterChain(ServerHttpSecurity http) throws Exception {
        return http
            .csrf().disable()
            .httpBasic().securityContextRepository(new WebSessionServerSecurityContextRepository())
            .and()
            .authorizeExchange()
            .pathMatchers(HttpMethod.GET, "/users/exists").permitAll()
            .pathMatchers("/session").authenticated()
            .pathMatchers("/users/{user}/**").access(this::currentUserMatchesPath)
            .anyExchange().authenticated()
            .and()
            .build();
    }

    private Mono<AuthorizationDecision> currentUserMatchesPath(Mono<Authentication> authentication, AuthorizationContext context) {
        return authentication
            .map((a) -> context.getVariables().get("user").equals(a.getName()))
            .map(AuthorizationDecision::new);
    }

    @Bean
    public ReactiveUserDetailsService userDetailsRepository(UserRepository users) {
        return (username) -> users
            .findByUsername(username)
            .map(user -> org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())
                .roles(user.getRoles().toArray(new String[0]))
                .disabled(!user.isActive())
                .accountLocked(!user.isActive())
                .credentialsExpired(!user.isActive())
                .accountExpired(!user.isActive())
                .build()

            );
    }

    @Bean
    public RouterFunction<ServerResponse> routes(
        UserHandler userHandler) {
        return route(GET("/session"), userHandler::current)
            .andRoute(DELETE("/session"), userHandler::logout)
            .andRoute(GET("/users/exists"), userHandler::exists);
    }
}


@Component
class UserHandler {

    private final UserRepository users;

    public UserHandler(UserRepository users) {
        this.users = users;
    }

    public Mono<ServerResponse> current(ServerRequest req) {
        return req.principal()
            .cast(UsernamePasswordAuthenticationToken.class)
            .map(u -> u.getPrincipal())
            .cast(UserDetails.class)
            .map(
                user -> {
                    Map<Object, Object> map = new HashMap<>();
                    map.put("username", user.getUsername());
                    map.put("roles", AuthorityUtils.authorityListToSet(user.getAuthorities()));
                    return map;
                }
            )
            .flatMap((user) -> ok().body(BodyInserters.fromObject(user)));
    }

    public Mono<ServerResponse> logout(ServerRequest req) {
        return req.session()
            .flatMap(WebSession::invalidate)
            .flatMap(v-> noContent().build());
    }

    public Mono<ServerResponse> exists(ServerRequest req) {

        Mono<ServerResponse> emailExists = Mono.justOrEmpty(req.queryParam("email"))
            .flatMap(email -> this.users.findByEmail(email)
                .flatMap(user -> ok().syncBody(Collections.singletonMap("exists", true)))
                .switchIfEmpty(ok().syncBody(Collections.singletonMap("exists", false)))
            )
            .switchIfEmpty(badRequest().syncBody(Collections.singletonMap("error", "request param username or email is required.")));

        return Mono.justOrEmpty(req.queryParam("username"))
            .flatMap(name -> this.users.findByUsername(name)
                .flatMap(user -> ok().syncBody(Collections.singletonMap("exists", true)))
                .switchIfEmpty(ok().syncBody(Collections.singletonMap("exists", false)))
            )
            .switchIfEmpty(emailExists);
    }
}

@Component
@Slf4j
class DataInitializer {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @EventListener(value = ApplicationReadyEvent.class)
    private void init() {
        log.info("start users initialization  ...");
        this.users
            .deleteAll()
            .thenMany(
                Flux
                    .just("user", "admin")
                    .flatMap(
                        username -> {
                            List<String> roles = "user".equals(username)
                                ? Arrays.asList("USER")
                                : Arrays.asList("USER", "ADMIN");

                            User user = User.builder().roles(roles).email(username + "@example.com").username(username).password(this.passwordEncoder.encode("password")).build();
                            return this.users.save(user);
                        }
                    )
            )
            .log()
            .subscribe(
                null,
                null,
                () -> log.info("done users initialization...")
            );
    }

}


interface UserRepository extends ReactiveCassandraRepository<User, String> {
    Mono<User> findByUsername(String username);

    // NOTE, be very careful about ALLOW FILTERING in real world apps, this
    // may affect scalability quite a lot. Filtering is efficient over primary
    // keys, not on all generic columns
    @Query("SELECT * FROM users WHERE email = ?0 ALLOW FILTERING")
    Mono<User> findByEmail(String email);
}
// @Component
// class UserRepository{
//    private final ReactiveCassandraTemplate template;
//
//    public UserRepository(ReactiveCassandraTemplate template) {
//        this.template = template;
//    }
//
//    public Mono<User> findByUsername(String username) {
//        return this.template
//                .selectOne(
//                        query(where("username").is(username)),
//                        User.class
//                );
//    }
//
//    public Mono<User> findByEmail(String email) {
//        return this.template
//                .selectOne(
//                        query(where("email").is(email)),
//                        User.class
//                );
//    }
//
//    public Mono<User> save(User user) {
//        return this.template.insert(user);
//    }
//
//    public Mono<Boolean> deleteAll() {
//        return this.template.delete(Query.empty(), User.class);
//    }
//}

@Data
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("users")
class User {

    @PrimaryKey
    private String username;
    private String password;

    private String email;

    @Builder.Default
    private boolean active = true;
    @Builder.Default
    private List<String> roles = new ArrayList<>();

}


package io.sustc.service.impl;

import io.sustc.dto.ReviewRecord;
import io.sustc.dto.UserRecord;
import io.sustc.dto.RecipeRecord;
import io.sustc.service.DatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * It's important to mark your implementation class with {@link Service} annotation.
 * As long as the class is annotated and implements the corresponding interface, you can place it under any package.
 */
@Service
@Slf4j
public class DatabaseServiceImpl implements DatabaseService {

    /**
     * Getting a {@link DataSource} instance from the framework, whose connections are managed by HikariCP.
     * <p>
     * Marking a field with {@link Autowired} annotation enables our framework to automatically
     * provide you a well-configured instance of {@link DataSource}.
     * Learn more: <a href="https://www.baeldung.com/spring-dependency-injection">Dependency Injection</a>
     */
    @Autowired
    private DataSource dataSource;

    @Override
    public List<Integer> getGroupMembers() {
        //TODO: replace this with your own student IDs in your group
        return Arrays.asList(12210000, 12210001, 12210002);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void importData(
            List<ReviewRecord> reviewRecords,
            List<UserRecord> userRecords,
            List<RecipeRecord> recipeRecords) {

        // ddl to create tables.
        createTables();

        // TODO: implement your import logic

    }


    private void createTables() {
        String[] createTableSQLs = {

                // users 表
                "CREATE TABLE IF NOT EXISTS users (" +
                        "author_id BIGSERIAL PRIMARY KEY, " +
                        "author_name VARCHAR(100) NOT NULL, " +
                        "gender VARCHAR(10) CHECK (gender IN ('Male','Female','UNKNOWN')), " +
                        "age INT, " +
                        "following INT DEFAULT 0, " +
                        "followers INT DEFAULT 0, " +
                        "password VARCHAR(255) NOT NULL, " +
                        "is_deleted BOOLEAN NOT NULL DEFAULT FALSE" +
                        ")",

                // follows 表（用户关注关系）
                "CREATE TABLE IF NOT EXISTS follows (" +
                        "blogger_id BIGINT REFERENCES users(author_id), " +
                        "follower_id BIGINT REFERENCES users(author_id), " +
                        "PRIMARY KEY (blogger_id, follower_id)" +
                        ")",

                // recipe 表
                "CREATE TABLE IF NOT EXISTS recipe (" +
                        "recipe_id BIGSERIAL PRIMARY KEY, " +
                        "author_id BIGINT REFERENCES users(author_id), " +
                        "dish_name VARCHAR(150) NOT NULL, " +
                        "date_published TIMESTAMP, " +
                        "cook_time VARCHAR(50), " +
                        "prep_time VARCHAR(50), " +
                        "description TEXT, " +
                        "category VARCHAR(100), " +
                        "aggr_rating REAL CHECK (aggr_rating >= 0 AND aggr_rating <= 5), " +
                        "review_cnt INT DEFAULT 0, " +
                        "recipe_yield VARCHAR(50), " +
                        "servings INT, " +
                        "calories REAL, " +
                        "fat REAL, " +
                        "saturated_fat REAL, " +
                        "cholesterol REAL, " +
                        "sodium REAL, " +
                        "carbohydrate REAL, " +
                        "fiber REAL, " +
                        "sugar REAL, " +
                        "protein REAL" +
                        ")",

                // review 表
                "CREATE TABLE IF NOT EXISTS review (" +
                        "review_id BIGSERIAL PRIMARY KEY, " +
                        "recipe_id BIGINT REFERENCES recipe(recipe_id), " +
                        "author_id BIGINT REFERENCES users(author_id), " +
                        "rating REAL, " +
                        "review TEXT, " +
                        "date_submitted TIMESTAMP, " +
                        "date_modified TIMESTAMP" +
                        ")",

                // likes_review 表（评论点赞）
                "CREATE TABLE IF NOT EXISTS likes_review (" +
                        "author_id BIGINT REFERENCES users(author_id), " +
                        "review_id BIGINT REFERENCES review(review_id), " +
                        "PRIMARY KEY (author_id, review_id)" +
                        ")",

                // ingredient 表
                "CREATE TABLE IF NOT EXISTS ingredient (" +
                        "ingredient_id BIGSERIAL PRIMARY KEY, " +
                        "ingredient_name VARCHAR(100) UNIQUE NOT NULL" +
                        ")",

                // has_ingredient 表（菜谱-配料）
                "CREATE TABLE IF NOT EXISTS has_ingredient (" +
                        "recipe_id BIGINT REFERENCES recipe(recipe_id), " +
                        "ingredient_id BIGINT REFERENCES ingredient(ingredient_id), " +
                        "PRIMARY KEY (recipe_id, ingredient_id)" +
                        ")"
        };

        for (String sql : createTableSQLs) {
            jdbcTemplate.execute(sql);
        }
    }




    /*
     * The following code is just a quick example of using jdbc datasource.
     * Practically, the code interacts with database is usually written in a DAO layer.
     *
     * Reference: [Data Access Object pattern](https://www.baeldung.com/java-dao-pattern)
     */

    @Override
    public void drop() {
        // You can use the default drop script provided by us in most cases,
        // but if it doesn't work properly, you may need to modify it.
        // This method will delete all the tables in the public schema.

        String sql = "DO $$\n" +
                "DECLARE\n" +
                "    tables CURSOR FOR\n" +
                "        SELECT tablename\n" +
                "        FROM pg_tables\n" +
                "        WHERE schemaname = 'public';\n" +
                "BEGIN\n" +
                "    FOR t IN tables\n" +
                "    LOOP\n" +
                "        EXECUTE 'DROP TABLE IF EXISTS ' || QUOTE_IDENT(t.tablename) || ' CASCADE;';\n" +
                "    END LOOP;\n" +
                "END $$;\n";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Integer sum(int a, int b) {
        String sql = "SELECT ?+?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, a);
            stmt.setInt(2, b);
            log.info("SQL: {}", stmt);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}

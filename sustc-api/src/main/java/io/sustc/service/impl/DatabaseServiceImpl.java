package io.sustc.service.impl;

import io.sustc.dto.ReviewRecord;
import io.sustc.dto.UserRecord;
import io.sustc.dto.RecipeRecord;
import io.sustc.service.DatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DatabaseServiceImpl implements DatabaseService {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public List<Integer> getGroupMembers() {
        return Arrays.asList(12410303, 12410148);
    }

    @Override
    @Transactional
    public void importData(
            List<ReviewRecord> reviewRecords,
            List<UserRecord> userRecords,
            List<RecipeRecord> recipeRecords) {

        createBasicTables();

        String userSql = "INSERT INTO users (author_id, author_name, gender, age, following, followers, password) VALUES ";
        executeBulkInsert(userSql, 7, userRecords, (u) -> new Object[]{
                u.getAuthorId(), u.getAuthorName(), u.getGender(), u.getAge(), u.getFollowing(), u.getFollowers(), u.getPassword()
        });

        // --- Follows ---
        List<Object[]> followArgs = userRecords.parallelStream()
                .filter(u -> u.getFollowingUsers() != null && u.getFollowingUsers().length > 0)
                .flatMap(u -> {
                    long followerId = u.getAuthorId();
                    return Arrays.stream(u.getFollowingUsers())
                            .distinct() // 去重
                            .mapToObj(bloggerId -> new Object[]{bloggerId, followerId});
                })
                .collect(Collectors.toList());

        executeBulkInsert("INSERT INTO follows (blogger_id, follower_id) VALUES ", 2, followArgs, x -> x);

        // --- Recipe ---
        String recipeSql = "INSERT INTO recipe (recipe_id, author_id, dish_name, date_published, cook_time, prep_time, description, category, aggr_rating, review_cnt, recipe_yield, servings, calories, fat, saturated_fat, cholesterol, sodium, carbohydrate, fiber, sugar, protein) VALUES ";
        executeBulkInsert(recipeSql, 21, recipeRecords, (r) -> new Object[]{
                r.getRecipeId(), r.getAuthorId(), r.getName(), r.getDatePublished(), r.getCookTime(), r.getPrepTime(), r.getDescription(), r.getRecipeCategory(), r.getAggregatedRating(), r.getReviewCount(), r.getRecipeYield(), r.getRecipeServings(), r.getCalories(), r.getFatContent(), r.getSaturatedFatContent(), r.getCholesterolContent(), r.getSodiumContent(), r.getCarbohydrateContent(), r.getFiberContent(), r.getSugarContent(), r.getProteinContent()
        });

        // --- Ingredients ---
        Set<String> uniqueIngredientNames = recipeRecords.parallelStream()
                .filter(r -> r.getRecipeIngredientParts() != null)
                .flatMap(r -> Arrays.stream(r.getRecipeIngredientParts())) // 使用 Arrays.stream() 处理数组
                .collect(Collectors.toSet());

        List<String> sortedIngredients = new ArrayList<>(uniqueIngredientNames);
        executeBulkInsert("INSERT INTO ingredient (ingredient_name) VALUES ", 1, sortedIngredients, (name) -> new Object[]{name});

        Map<String, Long> ingredientMap = new HashMap<>(uniqueIngredientNames.size());
        jdbcTemplate.query("SELECT ingredient_id, ingredient_name FROM ingredient", (rs) -> {
            ingredientMap.put(rs.getString("ingredient_name"), rs.getLong("ingredient_id"));
        });

        // --- Has_Ingredient ---
        List<Object[]> recipeIngRelations = recipeRecords.parallelStream()
                .filter(r -> r.getRecipeIngredientParts() != null && r.getRecipeIngredientParts().length > 0) // 数组没有 isEmpty()，用 length > 0
                .flatMap(r -> {
                    long rid = r.getRecipeId();
                    Set<String> parts = new HashSet<>();
                    Collections.addAll(parts, r.getRecipeIngredientParts()); // 将数组添加到 Set 进行去重

                    return parts.stream()
                            .map(ingredientMap::get)
                            .filter(Objects::nonNull)
                            .map(ingId -> new Object[]{rid, ingId});
                })
                .collect(Collectors.toList());

        executeBulkInsert("INSERT INTO has_ingredient (recipe_id, ingredient_id) VALUES ", 2, recipeIngRelations, x -> x);

        // --- Reviews ---
        String reviewSql = "INSERT INTO review (review_id, recipe_id, author_id, rating, review, date_submitted, date_modified) VALUES ";
        executeBulkInsert(reviewSql, 7, reviewRecords, (rr) -> new Object[]{
                rr.getReviewId(), rr.getRecipeId(), rr.getAuthorId(), rr.getRating(), rr.getReview(), rr.getDateSubmitted(), rr.getDateModified()
        });

        // --- Review Likes ---
        List<Object[]> likeRelations = reviewRecords.parallelStream()
                .filter(rr -> rr.getLikes() != null && rr.getLikes().length > 0)
                .flatMap(rr -> {
                    long rid = rr.getReviewId();
                    return Arrays.stream(rr.getLikes())
                            .distinct()
                            .mapToObj(uid -> new Object[]{uid, rid});
                })
                .collect(Collectors.toList());

        executeBulkInsert("INSERT INTO likes_review (author_id, review_id) VALUES ", 2, likeRelations, x -> x);

        addConstraints();
    }

    private <T> void executeBulkInsert(String sqlPrefix, int numParamsPerRecord, List<T> records, RowMapper<T> mapper) {
        if (records == null || records.isEmpty()) return;

        int batchSize = Math.max(1, 30000 / numParamsPerRecord);

        int total = records.size();
        for (int i = 0; i < total; i += batchSize) {
            int end = Math.min(i + batchSize, total);
            List<T> batch = records.subList(i, end);

            StringBuilder sql = new StringBuilder(sqlPrefix);
            String placeholders = "(" + String.join(",", Collections.nCopies(numParamsPerRecord, "?")) + ")";
            sql.append(String.join(",", Collections.nCopies(batch.size(), placeholders)));

            jdbcTemplate.update(sql.toString(), ps -> {
                int paramIndex = 1;
                for (T record : batch) {
                    Object[] args = mapper.map(record);
                    for (Object arg : args) {
                        ps.setObject(paramIndex++, arg);
                    }
                }
            });
        }
    }

    private interface RowMapper<T> {
        Object[] map(T t);
    }

    private void createBasicTables() {
        // 注意：这里没有任何 PRIMARY KEY 定义，只有最纯粹的数据列
        String sql = """
            CREATE TABLE IF NOT EXISTS users (
                    author_id      BIGINT, -- 稍后添加 PK
                    author_name    VARCHAR(255) NOT NULL,
                    gender         VARCHAR(10),
                    age            INT,
                    following      INT,
                    followers      INT,
                    password       VARCHAR(255) NOT NULL,
                    is_deleted     BOOLEAN NOT NULL DEFAULT FALSE
            );
            CREATE TABLE IF NOT EXISTS follows (
                    blogger_id  BIGINT,
                    follower_id BIGINT
            );
            CREATE TABLE IF NOT EXISTS recipe (
                    recipe_id       BIGINT, -- 稍后添加 PK
                    author_id       BIGINT,
                    dish_name       VARCHAR(255) NOT NULL,
                    date_published  TIMESTAMP,
                    cook_time       VARCHAR(50),
                    prep_time       VARCHAR(50),
                    description     TEXT,
                    category        VARCHAR(100),
                    aggr_rating     REAL,
                    review_cnt      INT,
                    recipe_yield    VARCHAR(50),
                    servings        INT,
                    calories        REAL,
                    fat             REAL,
                    saturated_fat   REAL,
                    cholesterol     REAL,
                    sodium          REAL,
                    carbohydrate    REAL,
                    fiber           REAL,
                    sugar           REAL,
                    protein         REAL
            );
            CREATE TABLE IF NOT EXISTS review (
                    review_id        BIGINT, -- 稍后添加 PK
                    recipe_id        BIGINT,
                    author_id        BIGINT,
                    rating           REAL,
                    review           TEXT,
                    date_submitted   TIMESTAMP,
                    date_modified    TIMESTAMP
            );
            CREATE TABLE IF NOT EXISTS likes_review (
                    author_id BIGINT,
                    review_id BIGINT
            );
            CREATE TABLE IF NOT EXISTS ingredient (
                    ingredient_id   BIGSERIAL,
                    ingredient_name VARCHAR(255) NOT NULL
            );
            CREATE TABLE IF NOT EXISTS has_ingredient (
                    recipe_id    BIGINT,
                    ingredient_id BIGINT
            );
        """;
        jdbcTemplate.execute(sql);
    }

    private void addConstraints() {
        String sql = """
            -- Users
            ALTER TABLE users ADD PRIMARY KEY (author_id);
            ALTER TABLE users ADD CONSTRAINT chk_users_gender CHECK (gender IN ('Male','Female','UNKNOWN'));

            -- Follows
            ALTER TABLE follows ADD PRIMARY KEY (blogger_id, follower_id);
            ALTER TABLE follows ADD CONSTRAINT fk_follows_blogger FOREIGN KEY (blogger_id) REFERENCES users(author_id);
            ALTER TABLE follows ADD CONSTRAINT fk_follows_follower FOREIGN KEY (follower_id) REFERENCES users(author_id);

            -- Recipe
            ALTER TABLE recipe ADD PRIMARY KEY (recipe_id);
            ALTER TABLE recipe ADD CONSTRAINT fk_recipe_author FOREIGN KEY (author_id) REFERENCES users(author_id);

            -- Ingredient
            ALTER TABLE ingredient ADD PRIMARY KEY (ingredient_id);
            ALTER TABLE ingredient ADD CONSTRAINT uq_ingredient_name UNIQUE (ingredient_name);

            -- Has Ingredient
            ALTER TABLE has_ingredient ADD PRIMARY KEY (recipe_id, ingredient_id);
            ALTER TABLE has_ingredient ADD CONSTRAINT fk_has_recipe FOREIGN KEY (recipe_id) REFERENCES recipe(recipe_id);
            ALTER TABLE has_ingredient ADD CONSTRAINT fk_has_ingredient FOREIGN KEY (ingredient_id) REFERENCES ingredient(ingredient_id);

            -- Review
            ALTER TABLE review ADD PRIMARY KEY (review_id);
            ALTER TABLE review ADD CONSTRAINT fk_review_recipe FOREIGN KEY (recipe_id) REFERENCES recipe(recipe_id);
            ALTER TABLE review ADD CONSTRAINT fk_review_author FOREIGN KEY (author_id) REFERENCES users(author_id);

            -- Likes Review
            ALTER TABLE likes_review ADD PRIMARY KEY (author_id, review_id);
            ALTER TABLE likes_review ADD CONSTRAINT fk_likes_author FOREIGN KEY (author_id) REFERENCES users(author_id);
            ALTER TABLE likes_review ADD CONSTRAINT fk_likes_review FOREIGN KEY (review_id) REFERENCES review(review_id);
        """;
        jdbcTemplate.execute(sql);
    }

    @Override
    public void drop() {
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
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
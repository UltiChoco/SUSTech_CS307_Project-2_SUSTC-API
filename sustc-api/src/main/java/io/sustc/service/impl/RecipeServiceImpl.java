package io.sustc.service.impl;

import io.sustc.dto.*;
import io.sustc.service.RecipeService;
import io.sustc.service.UserService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RecipeServiceImpl implements RecipeService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @Override
    public String getNameFromID(long id) {
        String sql = "SELECT dish_name FROM recipe WHERE recipe_id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, String.class, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public RecipeRecord getRecipeById(long recipeId) {
        // Use COALESCE to handle NULL values for primitive float fields in DTO
        String sql = """
                SELECT r.recipe_id, r.author_id, r.dish_name as name, r.date_published, r.cook_time, r.prep_time, 
                       r.description, r.category as recipe_category, 
                       COALESCE(r.aggr_rating, 0) as aggregated_rating, 
                       r.review_cnt as review_count, r.recipe_yield, r.servings as recipe_servings, 
                       COALESCE(r.calories, 0) as calories, 
                       COALESCE(r.fat, 0) as fat_content, 
                       COALESCE(r.saturated_fat, 0) as saturated_fat_content, 
                       COALESCE(r.cholesterol, 0) as cholesterol_content, 
                       COALESCE(r.sodium, 0) as sodium_content, 
                       COALESCE(r.carbohydrate, 0) as carbohydrate_content, 
                       COALESCE(r.fiber, 0) as fiber_content, 
                       COALESCE(r.sugar, 0) as sugar_content, 
                       COALESCE(r.protein, 0) as protein_content, 
                       u.author_name
                FROM recipe r 
                LEFT JOIN users u ON r.author_id = u.author_id 
                WHERE r.recipe_id = ?
                """;
        try {
            RecipeRecord record = jdbcTemplate.queryForObject(sql, new BeanPropertyRowMapper<>(RecipeRecord.class), recipeId);
            if (record != null) {
                record.setTotalTime(calculateTotalTime(record.getCookTime(), record.getPrepTime()));
                
                String ingSql = """
                        SELECT i.ingredient_name 
                        FROM has_ingredient hi 
                        JOIN ingredient i ON hi.ingredient_id = i.ingredient_id 
                        WHERE hi.recipe_id = ? 
                        ORDER BY i.ingredient_name
                        """;
                List<String> ingredients = jdbcTemplate.query(ingSql, (rs, rowNum) -> rs.getString("ingredient_name"), recipeId);
                record.setRecipeIngredientParts(ingredients.toArray(new String[0]));
            }
            return record;
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public PageResult<RecipeRecord> searchRecipes(String keyword, String category, Double minRating,
                                                  Integer page, Integer size, String sort) {
        if (page < 1 || size <= 0) {
            throw new IllegalArgumentException("Page must be >= 1 and size > 0");
        }

        // OPTIMIZATION: Separate WHERE clause to reuse and avoid JOINs in COUNT query
        StringBuilder whereClause = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        
        if (keyword != null && !keyword.isEmpty()) {
            whereClause.append(" AND (r.dish_name ILIKE ? OR r.description ILIKE ?) ");
            String pattern = "%" + keyword + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (category != null && !category.isEmpty()) {
            whereClause.append(" AND r.category = ? ");
            args.add(category);
        }
        if (minRating != null) {
            whereClause.append(" AND r.aggr_rating >= ? ");
            args.add(minRating);
        }

        // OPTIMIZATION: Count without JOINing users table
        String countSql = "SELECT COUNT(*) FROM recipe r " + whereClause.toString();
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, args.toArray());

        // Main Query
        StringBuilder fetchSql = new StringBuilder("""
                SELECT r.recipe_id, r.author_id, r.dish_name as name, r.date_published, r.cook_time, r.prep_time, 
                       r.description, r.category as recipe_category, 
                       COALESCE(r.aggr_rating, 0) as aggregated_rating, 
                       r.review_cnt as review_count, r.recipe_yield, r.servings as recipe_servings, 
                       COALESCE(r.calories, 0) as calories, 
                       COALESCE(r.fat, 0) as fat_content, 
                       COALESCE(r.saturated_fat, 0) as saturated_fat_content, 
                       COALESCE(r.cholesterol, 0) as cholesterol_content, 
                       COALESCE(r.sodium, 0) as sodium_content, 
                       COALESCE(r.carbohydrate, 0) as carbohydrate_content, 
                       COALESCE(r.fiber, 0) as fiber_content, 
                       COALESCE(r.sugar, 0) as sugar_content, 
                       COALESCE(r.protein, 0) as protein_content, 
                       u.author_name
                FROM recipe r 
                LEFT JOIN users u ON r.author_id = u.author_id 
                """);
        fetchSql.append(whereClause);

        if ("rating_desc".equals(sort)) {
            fetchSql.append(" ORDER BY r.aggr_rating DESC NULLS LAST ");
        } else if ("date_desc".equals(sort)) {
            fetchSql.append(" ORDER BY r.date_published DESC NULLS LAST ");
        } else if ("calories_asc".equals(sort)) {
            fetchSql.append(" ORDER BY r.calories ASC NULLS LAST ");
        } else {
            fetchSql.append(" ORDER BY r.recipe_id ASC ");
        }
        
        if (sort != null && !sort.isEmpty()) {
             fetchSql.append(", r.recipe_id ASC ");
        }

        fetchSql.append(" LIMIT ? OFFSET ? ");
        args.add(size);
        args.add((page - 1) * size);

        List<RecipeRecord> records = jdbcTemplate.query(fetchSql.toString(), new BeanPropertyRowMapper<>(RecipeRecord.class), args.toArray());

        // OPTIMIZATION: Batch fetch ingredients to avoid N+1 problem
        if (!records.isEmpty()) {
            records.forEach(r -> r.setTotalTime(calculateTotalTime(r.getCookTime(), r.getPrepTime())));

            List<Long> recipeIds = records.stream().map(RecipeRecord::getRecipeId).collect(Collectors.toList());
            String placeholders = String.join(",", Collections.nCopies(recipeIds.size(), "?"));
            String batchIngSql = String.format("""
                    SELECT hi.recipe_id, i.ingredient_name 
                    FROM has_ingredient hi 
                    JOIN ingredient i ON hi.ingredient_id = i.ingredient_id 
                    WHERE hi.recipe_id IN (%s) 
                    ORDER BY i.ingredient_name
                    """, placeholders);
            
            Map<Long, List<String>> ingredientsMap = new HashMap<>();
            jdbcTemplate.query(batchIngSql, (rs) -> {
                long rid = rs.getLong("recipe_id");
                String name = rs.getString("ingredient_name");
                ingredientsMap.computeIfAbsent(rid, k -> new ArrayList<>()).add(name);
            }, recipeIds.toArray());

            for (RecipeRecord record : records) {
                List<String> parts = ingredientsMap.getOrDefault(record.getRecipeId(), Collections.emptyList());
                record.setRecipeIngredientParts(parts.toArray(new String[0]));
            }
        }

        return PageResult.<RecipeRecord>builder()
                .items(records)
                .page(page)
                .size(size)
                .total(total == null ? 0 : total)
                .build();
    }

    @Override
    @Transactional
    public long createRecipe(RecipeRecord dto, AuthInfo auth) {
        long userId = userService.login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }
        if (dto.getName() == null || dto.getName().isEmpty()) {
            throw new IllegalArgumentException("Recipe name cannot be empty");
        }

        Long newId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(recipe_id), 0) + 1 FROM recipe", Long.class);

        String sql = """
                INSERT INTO recipe (recipe_id, author_id, dish_name, date_published, cook_time, prep_time, 
                description, category, aggr_rating, review_cnt, recipe_yield, servings, calories, fat, 
                saturated_fat, cholesterol, sodium, carbohydrate, fiber, sugar, protein) 
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        
        jdbcTemplate.update(sql, newId, userId, dto.getName(), Timestamp.from(Instant.now()), 
                dto.getCookTime(), dto.getPrepTime(), dto.getDescription(), dto.getRecipeCategory(),
                null, 0, dto.getRecipeYield(), dto.getRecipeServings(), dto.getCalories(), dto.getFatContent(),
                dto.getSaturatedFatContent(), dto.getCholesterolContent(), dto.getSodiumContent(),
                dto.getCarbohydrateContent(), dto.getFiberContent(), dto.getSugarContent(), dto.getProteinContent());

        if (dto.getRecipeIngredientParts() != null) {
            for (String ingName : dto.getRecipeIngredientParts()) {
                if (ingName == null || ingName.trim().isEmpty()) continue;
                String checkIng = "SELECT ingredient_id FROM ingredient WHERE ingredient_name = ?";
                Long ingId;
                try {
                    ingId = jdbcTemplate.queryForObject(checkIng, Long.class, ingName);
                } catch (EmptyResultDataAccessException e) {
                    jdbcTemplate.update("INSERT INTO ingredient (ingredient_name) VALUES (?)", ingName);
                    ingId = jdbcTemplate.queryForObject(checkIng, Long.class, ingName);
                }
                jdbcTemplate.update("INSERT INTO has_ingredient (recipe_id, ingredient_id) VALUES (?, ?)", newId, ingId);
            }
        }

        return newId;
    }

    @Override
    @Transactional
    public void deleteRecipe(long recipeId, AuthInfo auth) {
        long userId = userService.login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }

        String checkSql = "SELECT author_id FROM recipe WHERE recipe_id = ?";
        try {
            Long authorId = jdbcTemplate.queryForObject(checkSql, Long.class, recipeId);
            if (authorId == null || authorId != userId) {
                throw new SecurityException("User is not the author of this recipe");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Recipe does not exist");
        }

        jdbcTemplate.update("DELETE FROM likes_review WHERE review_id IN (SELECT review_id FROM review WHERE recipe_id = ?)", recipeId);
        jdbcTemplate.update("DELETE FROM review WHERE recipe_id = ?", recipeId);
        jdbcTemplate.update("DELETE FROM has_ingredient WHERE recipe_id = ?", recipeId);
        jdbcTemplate.update("DELETE FROM recipe WHERE recipe_id = ?", recipeId);
    }

    @Override
    @Transactional
    public void updateTimes(AuthInfo auth, long recipeId, String cookTimeIso, String prepTimeIso) {
        long userId = userService.login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }

        try {
            if (cookTimeIso != null) Duration.parse(cookTimeIso);
            if (prepTimeIso != null) Duration.parse(prepTimeIso);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ISO 8601 duration format");
        }

        String checkSql = "SELECT author_id FROM recipe WHERE recipe_id = ?";
        try {
            Long authorId = jdbcTemplate.queryForObject(checkSql, Long.class, recipeId);
            if (authorId == null || authorId != userId) {
                throw new SecurityException("User is not the author of this recipe");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Recipe does not exist");
        }

        StringBuilder updateSql = new StringBuilder("UPDATE recipe SET ");
        List<Object> params = new ArrayList<>();
        if (cookTimeIso != null) {
            updateSql.append("cook_time = ?, ");
            params.add(cookTimeIso);
        }
        if (prepTimeIso != null) {
            updateSql.append("prep_time = ?, ");
            params.add(prepTimeIso);
        }
        
        if (params.isEmpty()) return;

        updateSql.setLength(updateSql.length() - 2);
        updateSql.append(" WHERE recipe_id = ?");
        params.add(recipeId);

        jdbcTemplate.update(updateSql.toString(), params.toArray());
    }

    @Data @AllArgsConstructor
    private static class CalorieNode {
        long id;
        double cal;
    }

    @Override
    public Map<String, Object> getClosestCaloriePair() {
        // Fetch all valid calorie data and sort in Memory/DB
        String sql = "SELECT recipe_id, calories FROM recipe WHERE calories IS NOT NULL ORDER BY calories ASC, recipe_id ASC";
        
        List<CalorieNode> nodes = jdbcTemplate.query(sql, (rs, rowNum) -> 
            new CalorieNode(rs.getLong("recipe_id"), rs.getDouble("calories"))
        );

        if (nodes.size() < 2) return null;

        double minDiff = Double.MAX_VALUE;
        CalorieNode bestA = null;
        CalorieNode bestB = null;

        // Linear scan for adjacent closest pair O(N)
        for (int i = 0; i < nodes.size() - 1; i++) {
            CalorieNode a = nodes.get(i);
            CalorieNode b = nodes.get(i + 1);
            double diff = Math.abs(a.cal - b.cal);

            if (diff < minDiff) {
                minDiff = diff;
                bestA = a;
                bestB = b;
            } else if (diff == minDiff) {
                // Tie-breaking: Smaller RecipeA, then Smaller RecipeB
                long curMinId = Math.min(a.id, b.id);
                long curMaxId = Math.max(a.id, b.id);
                
                long bestMinId = Math.min(bestA.id, bestB.id);
                long bestMaxId = Math.max(bestA.id, bestB.id);
                
                if (curMinId < bestMinId) {
                    bestA = a; bestB = b;
                } else if (curMinId == bestMinId && curMaxId < bestMaxId) {
                    bestA = a; bestB = b;
                }
            }
        }

        if (bestA == null) return null;

        Map<String, Object> map = new HashMap<>();
        long id1 = Math.min(bestA.id, bestB.id);
        long id2 = Math.max(bestA.id, bestB.id);
        
        map.put("RecipeA", id1);
        map.put("RecipeB", id2);
        map.put("CaloriesA", (id1 == bestA.id) ? bestA.cal : bestB.cal);
        map.put("CaloriesB", (id2 == bestA.id) ? bestA.cal : bestB.cal);
        map.put("Difference", minDiff);
        return map;
    }

    @Override
    public List<Map<String, Object>> getTop3MostComplexRecipesByIngredients() {
        String sql = """
                SELECT r.recipe_id, r.dish_name, COUNT(hi.ingredient_id) as cnt 
                FROM recipe r 
                JOIN has_ingredient hi ON r.recipe_id = hi.recipe_id 
                GROUP BY r.recipe_id, r.dish_name 
                ORDER BY cnt DESC, r.recipe_id ASC 
                LIMIT 3
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("RecipeId", rs.getLong("recipe_id"));
            map.put("Name", rs.getString("dish_name"));
            map.put("IngredientCount", rs.getInt("cnt"));
            return map;
        });
    }

    private String calculateTotalTime(String cook, String prep) {
        try {
            Duration c = (cook != null && !cook.isEmpty()) ? Duration.parse(cook) : Duration.ZERO;
            Duration p = (prep != null && !prep.isEmpty()) ? Duration.parse(prep) : Duration.ZERO;
            Duration total = c.plus(p);
            if (total.isZero()) return null;
            return total.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

package io.sustc.service.impl;

import io.sustc.dto.*;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Calendar;
import java.util.TimeZone;

import java.util.concurrent.atomic.AtomicLong;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ===== login cache =====
    // Use (authorId,password) as cache key and never cache failed logins.
    private final Map<String, Long> authCache = new java.util.concurrent.ConcurrentHashMap<>();

    private long fastLogin(AuthInfo auth) {
        if (auth == null) return -1;
        if (auth.getPassword() == null || auth.getPassword().isEmpty()) return -1;
        String key = auth.getAuthorId() + ":" + auth.getPassword();
        Long cached = authCache.get(key);
        if (cached != null && cached != -1) {
            return cached;
        }
        long res = login(auth);
        if (res != -1) {
            authCache.put(key, res);
        }
        return res;
    }

    // 1. 将 Formatter 提出来作为静态常量，避免重复创建对象，大幅降低 CPU 开销
    private static final DateTimeFormatter[] DATE_FORMATTERS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US),
            DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US),
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss", Locale.US)
    };

    // 2. 使用内存原子计数器生成 ID, 解决并发主键冲突
    private final AtomicLong idGenerator = new AtomicLong(0);
    private volatile boolean idInitialized = false;

    @Override
    public long register(RegisterUserReq req) {
        if (req.getName() == null || req.getName().isEmpty() || req.getPassword() == null || req.getPassword().isEmpty()) {
            return -1;
        }
        if (req.getGender() == null || req.getGender() == RegisterUserReq.Gender.UNKNOWN) {
            return -1;
        }
        if (req.getBirthday() == null || req.getBirthday().isEmpty()) {
            return -1;
        }
        Integer age = calculateAgeFast(req.getBirthday());
        if (age == null || age <= 0) {
            return -1;
        }

        //ID 生成, 懒加载初始化
        if (!idInitialized) {
            synchronized (this) {
                if (!idInitialized) {
                    Long maxId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(author_id), 0) FROM users", Long.class);
                    idGenerator.set(maxId);
                    idInitialized = true;
                }
            }
        }
        long newId = idGenerator.incrementAndGet();

        String genderStr = req.getGender() == RegisterUserReq.Gender.MALE ? "Male" : "Female";

        try {
            // 4. 直接插入，去掉 SELECT COUNT(*)
            String sql = "INSERT INTO users (author_id, author_name, gender, age, following, followers, password, is_deleted) VALUES (?, ?, ?, ?, 0, 0, ?, false)";
            jdbcTemplate.update(sql, newId, req.getName(), genderStr, age, req.getPassword());

            return newId;
        } catch (DuplicateKeyException e) {
            return -1;
        }
    }

    private Integer calculateAgeFast(String birthday) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDate birthDate;
                try {
                    birthDate = LocalDate.parse(birthday, formatter);
                } catch (Exception e) {
                    birthDate = java.time.LocalDateTime.parse(birthday, formatter).toLocalDate();
                }
                return Period.between(birthDate, LocalDate.now()).getYears();
            } catch (Exception e) {
            }
        }
        return null;
    }

    @Override
    public long login(AuthInfo auth) {
        if (auth == null || auth.getPassword() == null || auth.getPassword().isEmpty()) {
            return -1;
        }
        String sql = "SELECT password, is_deleted FROM users WHERE author_id = ?";
        try {
            Map<String, Object> map = jdbcTemplate.queryForMap(sql, auth.getAuthorId());
            boolean isDeleted = (Boolean) map.get("is_deleted");
            String pwd = (String) map.get("password");
            
            if (isDeleted || !auth.getPassword().equals(pwd)) {
                return -1;
            }
            return auth.getAuthorId();
        } catch (EmptyResultDataAccessException e) {
            return -1;
        }
    }

    @Override
    @Transactional
    public boolean deleteAccount(AuthInfo auth, long userId) {
        long loggedInId = login(auth);
        if (loggedInId == -1) {
            throw new SecurityException("Invalid auth info");
        }
        if (loggedInId != userId) {
            throw new SecurityException("Cannot delete other user's account");
        }

        int deleted = jdbcTemplate.update(
                "UPDATE users SET is_deleted = true WHERE author_id = ? AND is_deleted = false",
                userId
        );
        if (deleted == 0) {
            return false;
        }

        jdbcTemplate.update(
                "UPDATE users SET followers = followers - 1 WHERE author_id IN " +
                        "(SELECT blogger_id FROM follows WHERE follower_id = ?)",
                userId
        );

        jdbcTemplate.update(
                "UPDATE users SET following = following - 1 WHERE author_id IN " +
                        "(SELECT follower_id FROM follows WHERE blogger_id = ?)",
                userId
        );

        jdbcTemplate.update("DELETE FROM follows WHERE follower_id = ? OR blogger_id = ?", userId, userId);

        jdbcTemplate.update("UPDATE users SET following = 0, followers = 0 WHERE author_id = ?", userId);

        return true;
    }

    @Override
    public boolean follow(AuthInfo auth, long followeeId) {
        long followerId = fastLogin(auth);
        if (followerId == -1) {
            throw new SecurityException("Invalid auth info");
        }
        if (followerId == followeeId) {
            throw new SecurityException("Cannot follow yourself");
        }

        // Unfollow 先删
        int rows = jdbcTemplate.update(
                "DELETE FROM follows WHERE follower_id = ? AND blogger_id = ?",
                followerId, followeeId
        );
        if (rows > 0) {
            updateCounters(followerId, followeeId, -1);
            return false;
        }

        // Follow (后插)
        int inserted = jdbcTemplate.update(
                "INSERT INTO follows (follower_id, blogger_id) " +
                        "SELECT ?, ? FROM users WHERE author_id = ? AND is_deleted = false",
                followerId, followeeId, followeeId
        );

        if (inserted == 0) {
            throw new SecurityException("User does not exist or deleted");
        }

        updateCounters(followerId, followeeId, 1);
        return true;
    }

    // 将两次Update合并为一次DB交互
    private void updateCounters(long followerId, long followeeId, int delta) {
        String sql =
                "UPDATE users SET " +
                        // 如果是发起者(follower)，更新 following 字段
                        "  following = following + (CASE WHEN author_id = ? THEN ? ELSE 0 END), " +
                        // 如果是目标(blogger)，更新 followers 字段
                        "  followers = followers + (CASE WHEN author_id = ? THEN ? ELSE 0 END) " +
                        "WHERE author_id IN (?, ?)";
        jdbcTemplate.update(sql, followerId, delta, followeeId, delta, followerId, followeeId);
    }


    @Override
    public UserRecord getById(long userId) {
        String sql = "SELECT author_id, author_name, gender, age, followers, following, password, is_deleted FROM users WHERE author_id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new BeanPropertyRowMapper<>(UserRecord.class), userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public void updateProfile(AuthInfo auth, String gender, Integer age) {
        long userId = fastLogin(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }

        if (gender != null && gender.isEmpty()) {
            gender = null;
        }
        if (age != null && age <= 0) {
            age = null;
        }
        if (gender == null && age == null) {
            return;
        }

        jdbcTemplate.update(
                """
                UPDATE users
                SET
                    gender = COALESCE(?, gender),
                    age    = COALESCE(?, age)
                WHERE author_id = ?
                """,
                gender,
                age,
                userId
        );
    }


    @Override
    public PageResult<FeedItem> feed(AuthInfo auth, int page, int size, String category) {
        long userId = login(auth);
        if (userId == -1) {
            throw new SecurityException("Invalid auth info");
        }
        if (page < 1) page = 1;
        if (size <= 0) size = 10;
        if (size > 200) size = 200;

        StringBuilder sqlBuilder = new StringBuilder("""
                SELECT r.recipe_id, r.dish_name as name, r.author_id, u.author_name, r.date_published, 
                       r.aggr_rating as aggregated_rating, r.review_cnt as review_count 
                FROM recipe r 
                JOIN follows f ON r.author_id = f.blogger_id 
                JOIN users u ON r.author_id = u.author_id 
                WHERE f.follower_id = ? 
                """);
        
        List<Object> args = new ArrayList<>();
        args.add(userId);

        if (category != null && !category.isEmpty()) {
            sqlBuilder.append(" AND r.category = ? ");
            args.add(category);
        }

        String countSql = "SELECT COUNT(*) FROM (" + sqlBuilder.toString() + ") as temp";
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, args.toArray());

        sqlBuilder.append(" ORDER BY r.date_published DESC, r.recipe_id DESC ");
        sqlBuilder.append(" LIMIT ? OFFSET ? ");
        args.add(size);
        args.add((page - 1) * size);

        List<FeedItem> items = jdbcTemplate.query(sqlBuilder.toString(), (rs, rowNum) -> {
            FeedItem item = new FeedItem();
            item.setRecipeId(rs.getLong("recipe_id"));
            item.setName(rs.getString("name"));
            item.setAuthorId(rs.getLong("author_id"));
            item.setAuthorName(rs.getString("author_name"));
            
            // Correctly interpret DB timestamp as UTC
            java.sql.Timestamp ts = rs.getTimestamp("date_published", Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            if (ts != null) {
                item.setDatePublished(ts.toInstant());
            }
            
            item.setAggregatedRating(rs.getDouble("aggregated_rating"));
            item.setReviewCount(rs.getInt("review_count"));
            return item;
        }, args.toArray());

        return PageResult.<FeedItem>builder()
                .items(items)
                .page(page)
                .size(size)
                .total(total == null ? 0 : total)
                .build();
    }

    @Override
    public Map<String, Object> getUserWithHighestFollowRatio() {
        String sql = """
                SELECT author_id, author_name, (CAST(followers AS FLOAT) / following) as ratio 
                FROM users 
                WHERE is_deleted = false AND following > 0 
                ORDER BY ratio DESC, author_id ASC 
                LIMIT 1
                """;
        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Map<String, Object> map = new HashMap<>();
                map.put("AuthorId", rs.getLong("author_id"));
                map.put("AuthorName", rs.getString("author_name"));
                map.put("Ratio", rs.getDouble("ratio"));
                return map;
            });
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}

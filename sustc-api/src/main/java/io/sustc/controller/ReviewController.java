package io.sustc.controller;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.PageResult;
import io.sustc.dto.RecipeRecord;
import io.sustc.dto.ReviewRecord;
import io.sustc.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/review")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @PostMapping
    public long addReview(@RequestHeader("Auth-Id") long authId,
                          @RequestHeader("Auth-Password") String password,
                          @RequestParam long recipeId,
                          @RequestParam int rating,
                          @RequestBody String reviewContent) {
        return reviewService.addReview(createAuth(authId, password), recipeId, rating, reviewContent);
    }

    @PutMapping("/{reviewId}")
    public void editReview(@RequestHeader("Auth-Id") long authId,
                           @RequestHeader("Auth-Password") String password,
                           @PathVariable long reviewId,
                           @RequestParam long recipeId,
                           @RequestParam int rating,
                           @RequestBody String reviewContent) {
        reviewService.editReview(createAuth(authId, password), recipeId, reviewId, rating, reviewContent);
    }

    @DeleteMapping("/{reviewId}")
    public void deleteReview(@RequestHeader("Auth-Id") long authId,
                             @RequestHeader("Auth-Password") String password,
                             @PathVariable long reviewId,
                             @RequestParam long recipeId) {
        reviewService.deleteReview(createAuth(authId, password), recipeId, reviewId);
    }

    @PostMapping("/{reviewId}/like")
    public long likeReview(@RequestHeader("Auth-Id") long authId,
                           @RequestHeader("Auth-Password") String password,
                           @PathVariable long reviewId) {
        return reviewService.likeReview(createAuth(authId, password), reviewId);
    }

    @PostMapping("/{reviewId}/unlike")
    public long unlikeReview(@RequestHeader("Auth-Id") long authId,
                             @RequestHeader("Auth-Password") String password,
                             @PathVariable long reviewId) {
        return reviewService.unlikeReview(createAuth(authId, password), reviewId);
    }

    @GetMapping("/recipe/{recipeId}")
    public PageResult<ReviewRecord> listByRecipe(@PathVariable long recipeId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size,
                                                 @RequestParam(required = false) String sort) {
        return reviewService.listByRecipe(recipeId, page, size, sort);
    }

    @PostMapping("/refresh/{recipeId}")
    public RecipeRecord refreshRecipeAggregatedRating(@PathVariable long recipeId) {
        return reviewService.refreshRecipeAggregatedRating(recipeId);
    }

    private AuthInfo createAuth(long id, String password) {
        return AuthInfo.builder().authorId(id).password(password).build();
    }
}

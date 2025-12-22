package io.sustc.controller;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.PageResult;
import io.sustc.dto.RecipeRecord;
import io.sustc.service.RecipeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/recipe")
public class RecipeController {

    @Autowired
    private RecipeService recipeService;

    @GetMapping("/{id}")
    public RecipeRecord getRecipeById(@PathVariable long id) {
        return recipeService.getRecipeById(id);
    }

    @GetMapping("/search")
    public PageResult<RecipeRecord> searchRecipes(@RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String category,
                                                  @RequestParam(required = false) Double minRating,
                                                  @RequestParam(defaultValue = "1") Integer page,
                                                  @RequestParam(defaultValue = "10") Integer size,
                                                  @RequestParam(required = false) String sort) {
        return recipeService.searchRecipes(keyword, category, minRating, page, size, sort);
    }

    @PostMapping
    public long createRecipe(@RequestHeader("Auth-Id") long authId,
                             @RequestHeader("Auth-Password") String password,
                             @RequestBody RecipeRecord dto) {
        return recipeService.createRecipe(dto, createAuth(authId, password));
    }

    @DeleteMapping("/{id}")
    public void deleteRecipe(@RequestHeader("Auth-Id") long authId,
                             @RequestHeader("Auth-Password") String password,
                             @PathVariable long id) {
        recipeService.deleteRecipe(id, createAuth(authId, password));
    }

    @PatchMapping("/{id}/time")
    public void updateTimes(@RequestHeader("Auth-Id") long authId,
                            @RequestHeader("Auth-Password") String password,
                            @PathVariable long id,
                            @RequestParam(required = false) String cookTime,
                            @RequestParam(required = false) String prepTime) {
        recipeService.updateTimes(createAuth(authId, password), id, cookTime, prepTime);
    }

    @GetMapping("/analytics/calories")
    public Map<String, Object> getClosestCaloriePair() {
        return recipeService.getClosestCaloriePair();
    }

    @GetMapping("/analytics/complexity")
    public List<Map<String, Object>> getTop3MostComplexRecipesByIngredients() {
        return recipeService.getTop3MostComplexRecipesByIngredients();
    }

    private AuthInfo createAuth(long id, String password) {
        return AuthInfo.builder().authorId(id).password(password).build();
    }
}

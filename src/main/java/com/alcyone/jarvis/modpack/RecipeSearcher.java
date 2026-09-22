package com.alcyone.jarvis.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Searches server-side recipes across vanilla and installed Direwolf20 mods.
 * Leverages Minecraft's RecipeManager and registry data.
 */
public class RecipeSearcher {

    public static JsonArray searchRecipes(MinecraftServer server, String query, int limit) {
        JsonArray results = new JsonArray();
        if (server == null || query == null || query.isBlank()) {
            return results;
        }

        String searchLower = query.trim().toLowerCase();
        RecipeManager recipeManager = server.getRecipeManager();
        Collection<RecipeHolder<?>> recipes = recipeManager.getRecipes();

        int maxLimit = Math.max(1, Math.min(limit, 30));
        int count = 0;

        for (RecipeHolder<?> holder : recipes) {
            if (count >= maxLimit) break;

            ResourceLocation recipeId = holder.id();
            Recipe<?> recipe = holder.value();
            ItemStack resultStack;
            try {
                resultStack = recipe.getResultItem(server.registryAccess());
            } catch (Exception e) {
                continue;
            }

            String resultItemId = resultStack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(resultStack.getItem()).toString();
            String resultItemName = resultStack.isEmpty() ? "" : resultStack.getHoverName().getString();

            boolean matchesOutput = resultItemId.toLowerCase().contains(searchLower)
                    || resultItemName.toLowerCase().contains(searchLower)
                    || recipeId.toString().toLowerCase().contains(searchLower);

            boolean matchesInput = false;
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            for (Ingredient ing : ingredients) {
                for (ItemStack inStack : ing.getItems()) {
                    String inId = BuiltInRegistries.ITEM.getKey(inStack.getItem()).toString();
                    if (inId.toLowerCase().contains(searchLower)) {
                        matchesInput = true;
                        break;
                    }
                }
                if (matchesInput) break;
            }

            if (matchesOutput || matchesInput) {
                JsonObject rJson = new JsonObject();
                rJson.addProperty("recipe_id", recipeId.toString());
                rJson.addProperty("mod", recipeId.getNamespace());
                rJson.addProperty("recipe_type", BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());

                JsonObject outJson = new JsonObject();
                outJson.addProperty("item", resultItemId);
                outJson.addProperty("name", resultItemName);
                outJson.addProperty("count", resultStack.isEmpty() ? 1 : resultStack.getCount());
                rJson.add("output", outJson);

                JsonArray inArr = new JsonArray();
                for (Ingredient ing : ingredients) {
                    if (ing.isEmpty()) continue;
                    JsonArray options = new JsonArray();
                    for (ItemStack inStack : ing.getItems()) {
                        JsonObject inObj = new JsonObject();
                        inObj.addProperty("item", BuiltInRegistries.ITEM.getKey(inStack.getItem()).toString());
                        inObj.addProperty("count", inStack.getCount());
                        options.add(inObj);
                    }
                    inArr.add(options);
                }
                rJson.add("inputs", inArr);
                rJson.addProperty("matches_as", matchesOutput ? "output" : "input");

                results.add(rJson);
                count++;
            }
        }

        return results;
    }
}

package me.prexorjustin.prexorcloud.controller.state;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;

import org.bson.Document;

/**
 * Mongo-backed {@link ScaleActionStore}: one document per group in the {@code scale_actions}
 * collection ({@code _id = group}, {@code at = Date}). Plain upsert -- scale-action timestamps are not
 * authority-critical, so they need no epoch fence (a stale value only costs one early scale, which the
 * next evaluation corrects).
 */
public final class MongoScaleActionStore implements ScaleActionStore {

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final MongoCollection<Document> scaleActions;

    public MongoScaleActionStore(MongoDatabase db) {
        this.scaleActions = db.getCollection("scale_actions");
    }

    @Override
    public void recordScaleAction(String group, Instant when) {
        scaleActions.replaceOne(
                Filters.eq("_id", group),
                new Document("_id", group).append("at", Date.from(when)),
                UPSERT);
    }

    @Override
    public Optional<Instant> getLastScaleAction(String group) {
        Document doc = scaleActions.find(Filters.eq("_id", group)).first();
        if (doc == null) return Optional.empty();
        Date at = doc.get("at", Date.class);
        return at == null ? Optional.empty() : Optional.of(at.toInstant());
    }
}

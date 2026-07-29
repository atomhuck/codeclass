package ru.repethelper.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lesson_series_price_changes",
        uniqueConstraints = @UniqueConstraint(name = "uq_series_price_change_index",
                columnNames = {"series_id", "effective_occurrence_index"}))
public class LessonSeriesPriceChange {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id", nullable = false)
    private LessonSeries series;

    @Column(name = "effective_occurrence_index", nullable = false)
    private int effectiveOccurrenceIndex;

    @Column(name = "price_rubles")
    private Integer priceRubles;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LessonSeriesPriceChange() {}

    LessonSeriesPriceChange(LessonSeries series, int effectiveOccurrenceIndex, Integer priceRubles) {
        this.series = series;
        this.effectiveOccurrenceIndex = effectiveOccurrenceIndex;
        this.priceRubles = priceRubles;
        this.createdAt = Instant.now();
    }

    public int getEffectiveOccurrenceIndex() { return effectiveOccurrenceIndex; }
    public Integer getPriceRubles() { return priceRubles; }
    void updatePrice(Integer priceRubles) { this.priceRubles = priceRubles; }
}

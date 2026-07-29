package ru.repethelper.web.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.repethelper.domain.LessonChangeScope;

public class LessonPriceForm {
    @Min(value = 1, message = "Минимальная стоимость — 1 ₽")
    @Max(value = 1_000_000, message = "Максимальная стоимость — 1 000 000 ₽")
    private Integer priceRubles;
    @NotNull
    private LessonChangeScope scope = LessonChangeScope.SINGLE;
    private boolean confirmPaidPriceChange;

    public Integer getPriceRubles() { return priceRubles; }
    public void setPriceRubles(Integer priceRubles) { this.priceRubles = priceRubles; }
    public LessonChangeScope getScope() { return scope; }
    public void setScope(LessonChangeScope scope) { this.scope = scope; }
    public boolean isConfirmPaidPriceChange() { return confirmPaidPriceChange; }
    public void setConfirmPaidPriceChange(boolean confirmPaidPriceChange) {
        this.confirmPaidPriceChange = confirmPaidPriceChange;
    }
}

package ru.repethelper.web.form;

import jakarta.validation.constraints.Size;

public class MeetingUrlForm {
    @Size(max = 2048, message = "Ссылка на звонок слишком длинная")
    private String meetingUrl;

    public String getMeetingUrl() { return meetingUrl; }
    public void setMeetingUrl(String meetingUrl) { this.meetingUrl = meetingUrl; }
}

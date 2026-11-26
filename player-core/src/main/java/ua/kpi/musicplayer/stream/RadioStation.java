package ua.kpi.musicplayer.stream;

import ua.kpi.musicplayer.visitor.MediaInfoVisitor;

public class RadioStation {

    private String name;
    private String country;
    private String tags;
    private String url;
    private String urlResolved;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUrlResolved() {
        return urlResolved;
    }

    public void setUrlResolved(String urlResolved) {
        this.urlResolved = urlResolved;
    }

    public String getPlaybackUrl() {
        if (urlResolved != null && !urlResolved.isBlank()) {
            return urlResolved;
        }
        return url;
    }

    public String accept(MediaInfoVisitor visitor) {
        return visitor.visitRadioStation(this);
    }

    @Override
    public String toString() {
        String title = name == null || name.isBlank() ? "Без назви" : name.trim();

        StringBuilder details = new StringBuilder();
        if (country != null && !country.isBlank()) {
            details.append(country.trim());
        }
        if (tags != null && !tags.isBlank()) {
            if (!details.isEmpty()) {
                details.append(", ");
            }
            details.append(tags.trim());
        }

        return details.isEmpty() ? title : title + " (" + details + ")";
    }
}

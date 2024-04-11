import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class DateTimeFormatting {
    public static void main(String[] args) {
        LocalDateTime now = LocalDateTime.now();

        // Create a DateTimeFormatter with timezone pattern
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

        // Convert the LocalDateTime to the timezone of your choice
        String formattedDateTime = now.atZone(ZoneId.of("America/New_York")).format(formatter);

        System.out.println("Formatted Date and Time with Timezone: " + formattedDateTime);
    }
}
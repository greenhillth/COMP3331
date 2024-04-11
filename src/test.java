import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class test {
    public static void main(String[] args) {
        ZonedDateTime now = ZonedDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

        String currtime = now.format(formatter);

        String header = """
                          Sender Log File \n
                Session date and time: %s\n
                operation    delta     flag     seq      size
                ------------------------------------------------
                    """;
        String data = String.format(header, currtime);
        System.out.print(data);
        String entry = String.format("%s          %-8.4f  %s      %-6d    %-4d", "snd", 0.0109, "SYN", 20, 996);

        System.out.println(entry);

    }
}
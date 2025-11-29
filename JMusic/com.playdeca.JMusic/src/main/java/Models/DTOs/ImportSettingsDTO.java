package Models.DTOs;

import lombok.Data;

@Data
public class ImportSettingsDTO {
    private String outputFormat;
    private int downloadThreads;
    private int searchThreads;
}

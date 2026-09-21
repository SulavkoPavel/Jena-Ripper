package org.jenaripper.dto;

import java.time.Instant;
import java.util.List;

public record ConnectionProfilesExport(int formatVersion, Instant exportedAt,
                                       List<ConnectionProfileTransfer> profiles) {}

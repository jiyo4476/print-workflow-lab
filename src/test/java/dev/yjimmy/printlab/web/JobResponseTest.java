package dev.yjimmy.printlab.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class JobResponseTest {

    @Test
    public void scrubReplacesWindowsAndUnixPathsWithPlaceholder() {
        assertThat(JobResponse.scrub("cannot read C:\\Users\\Jimmy\\hotfolder\\a.pdf: denied"))
                .isEqualTo("cannot read <path>: denied");
        assertThat(JobResponse.scrub("open /var/printlab/output/a.pdf failed"))
                .isEqualTo("open <path> failed");
    }

    @Test
    public void scrubLeavesOrdinaryMessagesAlone() {
        assertThat(JobResponse.scrub("bad page 3")).isEqualTo("bad page 3");
        assertThat(JobResponse.scrub("and/or the rest")).isEqualTo("and/or the rest");
        assertThat(JobResponse.scrub(null)).isNull();
    }
}

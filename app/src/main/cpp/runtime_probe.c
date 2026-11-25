#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) {
        printf("AUTOSUBHUB_RUNTIME_PROBE_OK\n");
        fflush(stdout);
        return 0;
    }

    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        printf("autosubhub-runtime-probe 0.1.0\n");
        return 0;
    }

    fprintf(stderr, "Use --self-test or --version\n");
    return 2;
}

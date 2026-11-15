#include "rolithax_schema.h"
#include <iostream>
#include <string>

int main() {
    const auto session = rolithax_schema_create();
    if (!session) return 1;
    std::string line;
    while (std::getline(std::cin, line)) {
        char* response = rolithax_schema_request(session, line.c_str());
        if (!response) { rolithax_schema_destroy(session); return 2; }
        std::cout << response << '\n' << std::flush;
        rolithax_schema_free(response);
    }
    rolithax_schema_destroy(session);
    return 0;
}

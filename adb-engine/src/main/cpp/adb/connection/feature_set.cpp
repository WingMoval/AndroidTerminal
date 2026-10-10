// FeatureSet implementation.
//
// Parses "features=a,b,c" from a CNXN banner.
// Reference: AOSP adb.cpp:349-402 (parse_banner), transport.cpp:1260-1262.

#include "adb/connection/feature_set.h"

namespace adb {

FeatureSet FeatureSet::parseBanner(std::string_view banner) {
    FeatureSet set;

    // Find "features=" in the banner.
    auto pos = banner.find("features=");
    if (pos == std::string_view::npos) {
        return set;  // No features field.
    }

    // Extract the value after "features=" up to the next ';'.
    pos += 9;  // strlen("features=")
    auto end = banner.find(';', pos);
    std::string_view features_str =
        (end == std::string_view::npos) ? banner.substr(pos) : banner.substr(pos, end - pos);

    // Split by ','.
    size_t start = 0;
    while (start < features_str.size()) {
        auto comma = features_str.find(',', start);
        std::string_view token =
            (comma == std::string_view::npos) ? features_str.substr(start)
                                              : features_str.substr(start, comma - start);
        if (!token.empty()) {
            set.features_.insert(std::string(token));
        }
        if (comma == std::string_view::npos) break;
        start = comma + 1;
    }

    return set;
}

bool FeatureSet::contains(const std::string& feature) const {
    return features_.count(feature) > 0;
}

}  // namespace adb

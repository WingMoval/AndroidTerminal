// FeatureSet: ADB feature negotiation helpers.
//
// Reference v1 (adb_protocol_reference.md, transport.cpp:1260-1262):
//   CanUseFeature(remote_set, feature) =
//     remote_set contains feature AND local_supported contains feature
//
// Banner format (AOSP adb.cpp:349-402, parse_banner):
//   host::features=shell_v2,cmd,stat_v2,...
//   device::ro.product.name=X;ro.product.model=Y;features=shell_v2,cmd,...

#pragma once

#include <set>
#include <string>
#include <string_view>

namespace adb {

class FeatureSet {
public:
    FeatureSet() = default;

    // Parse a CNXN banner string. Extracts the "features=..." portion.
    // Handles: missing features field, empty features, unknown features.
    static FeatureSet parseBanner(std::string_view banner);

    // Check if this set contains the given feature.
    bool contains(const std::string& feature) const;

    // The set of feature names.
    const std::set<std::string>& values() const { return features_; }

    bool empty() const { return features_.empty(); }

private:
    std::set<std::string> features_;
};

}  // namespace adb

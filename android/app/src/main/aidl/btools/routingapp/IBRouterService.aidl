// Public interoperability interface from BRouter (MIT).
// Copyright (c) 2019 BRouter contributors.
// https://github.com/abrensch/brouter/blob/ff7b616df988dbd82c9869368638065a7595b11d/LICENSE
package btools.routingapp;
interface IBRouterService {
    String getTrackFromParams(in Bundle params);
}

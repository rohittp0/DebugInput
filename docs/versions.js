(() => {
  const nodes = document.querySelectorAll("[data-debug-input-version]");
  if (nodes.length === 0) return;

  const metadataUrl = "https://maven.rohittp.com/com/rohittp/debug-input/com.rohittp.debug-input.gradle.plugin/maven-metadata.xml";

  fetch(metadataUrl, { cache: "no-store" })
    .then((response) => {
      if (!response.ok) throw new Error(`Metadata request failed with HTTP ${response.status}`);
      return response.text();
    })
    .then((metadata) => {
      const xml = new DOMParser().parseFromString(metadata, "application/xml");
      if (xml.querySelector("parsererror")) throw new Error("Metadata response is not valid XML");

      const version = xml.querySelector("versioning > release")?.textContent?.trim();
      if (!version) throw new Error("Metadata does not contain a release version");

      nodes.forEach((node) => { node.textContent = version; });
    })
    .catch((error) => {
      console.error("Unable to load the latest published DebugInput version.", error);
    });
})();

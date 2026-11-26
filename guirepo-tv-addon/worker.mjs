import { handleRequest } from "./src/addon.mjs";

export default {
  async fetch(request) {
    return handleRequest(request);
  }
};

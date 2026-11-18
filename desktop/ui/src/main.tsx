// No StrictMode: the start intent is read once, and a dev double-effect would consume it twice.
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./styles/index.css";

createRoot(document.getElementById("root")!).render(
  <App />,
);

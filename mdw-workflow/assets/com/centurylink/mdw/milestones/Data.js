class Data {
    
  constructor(milestones) {
    this.milestones = milestones;
    this.items = [];
    this.edges = [];
    this.idCtr = 0;
    this.depth = 0;
    this.maxDepth = 0;
    this.add(milestones);
  }
  
  add(milestone) {
    this.depth++;
    let item = milestone.milestone;
    item.id = this.idCtr;
    this.items.push(item);
    if (milestone.children) {
      milestone.children.forEach(child => {
        this.edges.push({
          from: item.id,
          to: ++this.idCtr
        });
        this.add(child);
      }, this);
    }
    if (this.depth > this.maxDepth) {
      this.maxDepth = this.depth;
    }
    this.depth--;
  }
}

export default Data; 